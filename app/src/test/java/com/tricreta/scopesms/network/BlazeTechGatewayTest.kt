package com.tricreta.scopesms.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Phase 5 exit criteria: success, each documented error, and
 * timeout/no-connectivity.
 *
 * These are the failure paths CLAUDE.md calls "the ones that matter most now
 * that sending is online-only". Each asserts the *typed reason* and its
 * retryable flag, not merely that something failed: the reason is what the agent
 * reads in the activity log, and `retryable` is what the Phase 5b queue branches
 * on. Getting the flag wrong either drops a customer's SMS or spins the queue
 * against a wall forever.
 *
 * **Two layers on purpose.** Response *shapes* go through a real MockWebServer,
 * so Moshi and the `@Json` names are genuinely exercised over HTTP — that's
 * where a hyphenated `response-code` or a renamed field would break. Transport
 * *exceptions* are injected through a fake [ScopeSmsApi] instead: whether OkHttp
 * raises SocketTimeoutException on a slow socket is OkHttp's contract, not ours,
 * and asserting it through a real socket buys nothing but flake.
 */
class BlazeTechGatewayTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: BlazeTechGateway

    private val credentials = GatewayCredentials(apiKey = "test-key-123", senderId = "SCOPE SMS")
    private var provided: GatewayCredentials? = credentials

    private val credentialsProvider = object : GatewayCredentialsProvider {
        override suspend fun credentials() = provided
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = BlazeTechGateway.create(
            credentialsProvider = credentialsProvider,
            baseUrl = server.url("/v1/").toString(),
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueueJson(code: Int, body: String) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    /** Injects a transport-level outcome without a socket. */
    private fun gatewayThatThrows(error: Throwable) = BlazeTechGateway(
        object : ScopeSmsApi {
            override suspend fun sendSms(request: SendSmsRequest): Response<SendSmsResponse> =
                throw error
        },
        credentialsProvider,
    )

    // --- The wire contract --------------------------------------------------

    @Test
    fun `the request serialises to the gateway's documented field names`() {
        // Asserted on the JSON itself, because these names are a contract with a
        // server we can't compile against. Renaming `sender_id` to `senderId`
        // would be invisible in Kotlin and rejected in production.
        val json = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            .adapter(SendSmsRequest::class.java)
            .toJson(SendSmsRequest("Bundles: 20 bob = 1GB", "0712345678", "SCOPE SMS", "key-1"))

        assertThat(json).contains("\"message\":\"Bundles: 20 bob = 1GB\"")
        assertThat(json).contains("\"phone\":\"0712345678\"")
        assertThat(json).contains("\"sender_id\":\"SCOPE SMS\"")
        assertThat(json).contains("\"api_key\":\"key-1\"")
    }

    @Test
    fun `documented success response is parsed into Sent`() = runTest {
        // The hyphen in "response-code" is the part worth proving — it can't be a
        // Kotlin identifier, so it only works via the @Json name.
        enqueueJson(
            200,
            """{"response-code":200,"messageid":"msg-9981","mobile":"0700000000","networkid":"1"}""",
        )

        val outcome = gateway.sendSms(phone = "254700000000", message = "Habari Bonke")

        assertThat(outcome).isInstanceOf(SendOutcome.Sent::class.java)
        val sent = outcome as SendOutcome.Sent
        assertThat(sent.messageId).isEqualTo("msg-9981")
        assertThat(sent.mobile).isEqualTo("0700000000")
        assertThat(sent.networkId).isEqualTo("1")
    }

    // --- The LIVE gateway shape (the reported 5-retry bug) ------------------

    @Test
    fun `the live success shape (status success, statusCode 200) is Sent, not retried`() = runTest {
        // The real endpoint's response, captured 2026-07. It carries NEITHER the
        // documented response-code NOR messageid — success is status/statusCode and
        // the id is transactionId (msgId is empty on the immediate response). This
        // exact body was read as "unexpected" (retryable) and sent 5 times, then
        // logged failed, while all 5 went out (confirmed on the SCOPE dashboard).
        enqueueJson(
            200,
            """{"status":"success","mobile":"254727921038","invalidMobile":"","transactionId":"8466326473775335661","statusCode":"200","reason":"success","msgId":"","requestTime":"2026-07-17 00:20:09"}""",
        )

        val outcome = gateway.sendSms("0727921038", "Habari")

        assertThat(outcome).isInstanceOf(SendOutcome.Sent::class.java)
        val sent = outcome as SendOutcome.Sent
        // No msgId yet → the id falls back to transactionId so the log can track it.
        assertThat(sent.messageId).isEqualTo("8466326473775335661")
        assertThat(sent.mobile).isEqualTo("254727921038")
    }

    @Test
    fun `a live-shape response prefers msgId over transactionId when present`() = runTest {
        enqueueJson(200, """{"status":"success","statusCode":"200","transactionId":"txn-1","msgId":"MSG-9"}""")

        val outcome = gateway.sendSms("0727921038", "hi")

        assertThat((outcome as SendOutcome.Sent).messageId).isEqualTo("MSG-9")
    }

    @Test
    fun `a live-shape response that flags the number invalid is terminal InvalidPhone`() = runTest {
        // Defensive: a send that is NOT success and names the rejected number in
        // invalidMobile must be terminal, not retried.
        enqueueJson(
            200,
            """{"status":"error","reason":"invalid mobile","invalidMobile":"254700000000","statusCode":"400"}""",
        )

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isInstanceOf(SendFailure.InvalidPhone::class.java)
        assertThat(reason.retryable).isFalse()
    }

    // --- Terminal failures --------------------------------------------------

    @Test
    fun `401 maps to InvalidApiKey and is terminal`() = runTest {
        enqueueJson(401, """{"message":"Invalid API key"}""")

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.InvalidApiKey)
        assertThat(reason.retryable).isFalse()
    }

    @Test
    fun `unregistered sender ID is terminal so the queue stops instead of looping`() = runTest {
        // CLAUDE.md: the app cannot fix this — the sender ID must be approved on
        // SCOPE's side — so it must surface "rather than retrying forever".
        enqueueJson(403, """{"message":"Sender ID not registered"}""")

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.UnregisteredSenderId)
        assertThat(reason.retryable).isFalse()
    }

    @Test
    fun `invalid phone error body is terminal`() = runTest {
        enqueueJson(400, """{"message":"Invalid phone number"}""")

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isInstanceOf(SendFailure.InvalidPhone::class.java)
        assertThat(reason.retryable).isFalse()
    }

    @Test
    fun `unrecognised 400 is rejected with the gateway's own text preserved`() = runTest {
        enqueueJson(400, """{"message":"Malformed payload"}""")

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isInstanceOf(SendFailure.Rejected::class.java)
        assertThat(reason.retryable).isFalse()
        // The agent needs the gateway's wording to diagnose an undocumented case.
        assertThat(reason.description).contains("Malformed payload")
    }

    // --- Retryable failures -------------------------------------------------

    @Test
    fun `429 maps to RateLimited and is retryable`() = runTest {
        enqueueJson(429, """{"message":"Too many requests"}""")

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.RateLimited)
        assertThat(reason.retryable).isTrue()
    }

    @Test
    fun `500 maps to ServerError and is retryable`() = runTest {
        enqueueJson(500, "")

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.ServerError(500))
        assertThat(reason.retryable).isTrue()
    }

    @Test
    fun `insufficient balance is retryable so a top-up still gets the SMS out`() = runTest {
        enqueueJson(403, """{"message":"Insufficient balance"}""")

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        // Deliberately retryable despite sharing a 403 with sender-ID errors: a
        // top-up takes the agent a minute and the customer is still waiting.
        // See SendFailure.InsufficientBalance for the full argument.
        assertThat(reason).isEqualTo(SendFailure.InsufficientBalance)
        assertThat(reason.retryable).isTrue()
    }

    @Test
    fun `timeout is retryable, not a crash`() = runTest {
        val reason = gatewayThatThrows(SocketTimeoutException("timeout"))
            .sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.Timeout)
        assertThat(reason.retryable).isTrue()
    }

    @Test
    fun `no connectivity is retryable, not a crash`() = runTest {
        // The realistic case, per CLAUDE.md constraint 2: a payment lands while
        // the phone has no data.
        val reason = gatewayThatThrows(IOException("no route to host"))
            .sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.NoConnectivity)
        assertThat(reason.retryable).isTrue()
    }

    @Test
    fun `an unreachable host is reported as no connectivity`() = runTest {
        // End-to-end through OkHttp, unlike the injected case above.
        server.close()

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.NoConnectivity)
    }

    // --- Shapes that would otherwise slip through as "sent" -----------------

    @Test
    fun `error body under HTTP 200 is not mistaken for success`() = runTest {
        // The trap: trusting the HTTP status alone would mark the job SENT and
        // the customer's SMS would vanish with the log claiming it went out.
        enqueueJson(200, """{"response-code":401,"message":"Invalid API key"}""")

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.InvalidApiKey)
    }

    @Test
    fun `a response-code 200 with no messageid counts as sent, not failed`() = runTest {
        // The client's warning: the gateway can confirm delivery without an id.
        // A body response-code of 200 is a positive delivery signal, so this must
        // be Sent (with a blank id we can't track) rather than a failure — a
        // failure here would log a delivered SMS as failed and have the agent
        // chase a customer who already got their reply.
        enqueueJson(200, """{"response-code":200}""")

        val outcome = gateway.sendSms("0700000000", "hi")

        assertThat(outcome).isInstanceOf(SendOutcome.Sent::class.java)
    }

    @Test
    fun `a success whose message mentions the number is not mistaken for invalid phone`() = runTest {
        // The reported bug, exactly. The gateway returned a real messageid AND a
        // human message that happened to contain the recipient number; the old
        // code ran the "phone"/"number" text classifier before checking for the
        // id, so a delivered SMS came back as InvalidPhone. A delivery signal must
        // win over the wording.
        enqueueJson(
            200,
            """{"response-code":200,"messageid":"msg-42","message":"Message submitted to number 254700000000"}""",
        )

        val outcome = gateway.sendSms("0700000000", "hi")

        assertThat(outcome).isInstanceOf(SendOutcome.Sent::class.java)
        assertThat((outcome as SendOutcome.Sent).messageId).isEqualTo("msg-42")
    }

    @Test
    fun `a 200 that is neither sent nor a known error is Unexpected, not silently dropped`() = runTest {
        // No messageid, no success code, and text we don't recognise. This is the
        // genuinely-ambiguous case, and it must surface rather than be called sent.
        enqueueJson(200, """{"foo":"bar"}""")

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isInstanceOf(SendFailure.Unexpected::class.java)
    }

    @Test
    fun `undocumented body does not escape as an exception`() = runTest {
        enqueueJson(200, "<html>502 Bad Gateway</html>")

        val outcome = gateway.sendSms("0700000000", "hi")

        // A Moshi parse error must become a typed failure — if it propagated,
        // the worker would report a generic crash and the reason would be lost.
        assertThat(outcome).isInstanceOf(SendOutcome.Failed::class.java)
        assertThat(outcome.failureReason().retryable).isTrue()
    }

    // --- Guards that cost nothing and save a round trip ---------------------

    @Test
    fun `missing credentials fail terminally without touching the network`() = runTest {
        provided = null

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.InvalidApiKey)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `unparseable recipient fails terminally without touching the network`() = runTest {
        val reason = gateway.sendSms("not-a-number", "hi").failureReason()

        assertThat(reason).isInstanceOf(SendFailure.InvalidPhone::class.java)
        assertThat(reason.retryable).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    // --- Secrets ------------------------------------------------------------

    @Test
    fun `the API key never appears in toString output`() {
        // Constraint 7. toString is what lands in a stack trace or a stray log
        // line, and logcat is readable by other apps on some OEM builds.
        val request = SendSmsRequest("body", "0700000000", "SCOPE SMS", "super-secret-key")

        assertThat(request.toString()).doesNotContain("super-secret-key")
        assertThat(request.toString()).contains("***")
        assertThat(credentials.toString()).doesNotContain("test-key-123")
    }

    private fun SendOutcome.failureReason(): SendFailure {
        assertThat(this).isInstanceOf(SendOutcome.Failed::class.java)
        return (this as SendOutcome.Failed).reason
    }
}
