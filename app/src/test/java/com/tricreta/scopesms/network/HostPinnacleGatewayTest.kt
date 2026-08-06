package com.tricreta.scopesms.network

import com.google.common.truth.Truth.assertThat
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
 * HostPinnacle's send path, mirroring [BlazeTechGatewayTest] — same exit
 * criteria (CLAUDE.md testing expectations): success, each documented error,
 * and timeout/no-connectivity. Most of the *interpretation* logic is already
 * proven there, since both gateways share [SendSmsResponseInterpreter]; what's
 * specific to this class and worth its own coverage is the wire shape
 * (form-encoded body + header auth, not JSON) and the international phone
 * format.
 */
class HostPinnacleGatewayTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: HostPinnacleGateway

    private val credentials = GatewayCredentials(apiKey = "test-key-123", senderId = "MYBIZ")
    private var provided: GatewayCredentials? = credentials

    private val credentialsProvider = object : GatewayCredentialsProvider {
        override suspend fun credentials() = provided
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = HostPinnacleGateway.create(
            credentialsProvider = credentialsProvider,
            baseUrl = server.url("/SMSApi/").toString(),
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
    private fun gatewayThatThrows(error: Throwable) = HostPinnacleGateway(
        object : HostPinnacleApi {
            override suspend fun sendSms(
                apiKey: String,
                mobile: String,
                message: String,
                senderId: String,
                sendMethod: String,
                msgType: String,
                duplicateCheck: String,
                output: String,
            ): Response<SendSmsResponse> = throw error

            override suspend fun checkStatus(
                apiKey: String,
                uuid: String,
                fromDate: String,
                toDate: String,
                output: String,
            ): Response<StatusCheckResponse> = throw error
        },
        credentialsProvider,
    )

    // --- The wire contract ---------------------------------------------------

    @Test
    fun `the request is form-encoded, not JSON, with the apikey header and international phone`() = runTest {
        enqueueJson(200, """{"status":"success","statusCode":"200","transactionId":"txn-1"}""")

        gateway.sendSms(phone = "254700000000", message = "Habari Bonke")

        val request = server.takeRequest()
        assertThat(request.headers["apikey"]).isEqualTo("test-key-123")
        assertThat(request.headers["Content-Type"]).contains("application/x-www-form-urlencoded")
        val body = request.body!!.utf8()
        // Phone is international (254...), not local (07...) — the opposite of
        // BlazeTech, and the whole reason PhoneNumbers.toInternationalFormat exists.
        assertThat(body).contains("mobile=254700000000")
        assertThat(body).contains("msg=")
        assertThat(body).contains("senderid=MYBIZ")
        assertThat(body).contains("sendMethod=quick")
        assertThat(body).contains("msgType=text")
        assertThat(body).contains("duplicatecheck=true")
        assertThat(body).contains("output=json")
        // The API key travels in the header, never the body.
        assertThat(body).doesNotContain("test-key-123")
    }

    @Test
    fun `a locally-formatted phone is converted to international before sending`() = runTest {
        enqueueJson(200, """{"status":"success","statusCode":"200","transactionId":"txn-1"}""")

        gateway.sendSms(phone = "0700000000", message = "hi")

        val body = server.takeRequest().body!!.utf8()
        assertThat(body).contains("mobile=254700000000")
    }

    // --- The live success shape (shared with BlazeTech, verified here too) ---

    @Test
    fun `the documented success shape (status success, statusCode 200) is Sent`() = runTest {
        enqueueJson(
            200,
            """{"status":"success","mobile":"254727921038","invalidMobile":"","transactionId":"8466326473775335661","statusCode":"200","reason":"success"}""",
        )

        val outcome = gateway.sendSms("0727921038", "Habari")

        assertThat(outcome).isInstanceOf(SendOutcome.Sent::class.java)
        val sent = outcome as SendOutcome.Sent
        assertThat(sent.messageId).isEqualTo("8466326473775335661")
        assertThat(sent.mobile).isEqualTo("254727921038")
    }

    // --- Terminal failures -----------------------------------------------------

    @Test
    fun `401 maps to InvalidApiKey and is terminal`() = runTest {
        enqueueJson(401, """{"reason":"Invalid API key"}""")

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.InvalidApiKey)
        assertThat(reason.retryable).isFalse()
    }

    @Test
    fun `unregistered sender ID is terminal so the queue stops instead of looping`() = runTest {
        enqueueJson(403, """{"reason":"Sender ID not registered"}""")

        val reason = gateway.sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.UnregisteredSenderId)
        assertThat(reason.retryable).isFalse()
    }

    // --- Retryable failures ------------------------------------------------

    @Test
    fun `429 maps to RateLimited and is retryable`() = runTest {
        enqueueJson(429, """{"reason":"Too many requests"}""")

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
    fun `timeout is retryable, not a crash`() = runTest {
        val reason = gatewayThatThrows(SocketTimeoutException("timeout"))
            .sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.Timeout)
        assertThat(reason.retryable).isTrue()
    }

    @Test
    fun `no connectivity is retryable, not a crash`() = runTest {
        val reason = gatewayThatThrows(IOException("no route to host"))
            .sendSms("0700000000", "hi").failureReason()

        assertThat(reason).isEqualTo(SendFailure.NoConnectivity)
        assertThat(reason.retryable).isTrue()
    }

    @Test
    fun `undocumented body does not escape as an exception`() = runTest {
        enqueueJson(200, "<html>502 Bad Gateway</html>")

        val outcome = gateway.sendSms("0700000000", "hi")

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

    // --- checkStatus (delivery-status lookup, HostPinnacle-only) -------------

    @Test
    fun `checkStatus with a real status entry returns Known verbatim`() = runTest {
        enqueueJson(
            200,
            """
            {"response":{"api":"send","action":"status","status":"success","msg":"success","code":"200","count":1,
            "report_statusList":[{"status":{"uuId":"8359251506264886974","msgId":"QudUrwhMuIGkbs2",
            "mobileNo":"919999999999","Status":"FAILED","Cause":"Unknown User"}}]}}
            """.trimIndent(),
        )

        val outcome = gateway.checkStatus("8359251506264886974")

        assertThat(outcome).isEqualTo(DeliveryStatusOutcome.Known(status = "FAILED", cause = "Unknown User"))
    }

    @Test
    fun `checkStatus sends uuid and a fromdate-todate window, form-encoded`() = runTest {
        enqueueJson(200, """{"response":{"report_statusList":[]}}""")

        gateway.checkStatus("txn-123")

        val body = server.takeRequest().body!!.utf8()
        assertThat(body).contains("uuid=txn-123")
        assertThat(body).contains("fromdate=")
        assertThat(body).contains("todate=")
        assertThat(body).contains("output=json")
    }

    @Test
    fun `checkStatus with an empty report list is Failed, not a crash`() = runTest {
        enqueueJson(200, """{"response":{"report_statusList":[]}}""")

        val outcome = gateway.checkStatus("txn-unknown")

        assertThat(outcome).isInstanceOf(DeliveryStatusOutcome.Failed::class.java)
        assertThat((outcome as DeliveryStatusOutcome.Failed).reason).contains("No status found")
    }

    @Test
    fun `checkStatus on an unreachable gateway is Failed, not a crash`() = runTest {
        server.close()

        val outcome = gateway.checkStatus("txn-123")

        assertThat(outcome).isInstanceOf(DeliveryStatusOutcome.Failed::class.java)
    }

    @Test
    fun `checkStatus with missing credentials fails terminally without touching the network`() = runTest {
        provided = null

        val outcome = gateway.checkStatus("txn-123")

        assertThat(outcome).isEqualTo(DeliveryStatusOutcome.Failed("Gateway not set up"))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `BlazeTechGateway checkStatus is NotSupported, unchanged by the interface default`() = runTest {
        // Proves adding checkStatus to SmsGateway needed zero BlazeTech code
        // changes — it inherits the interface's default implementation.
        val blazeTech = BlazeTechGateway.create(
            credentialsProvider = object : GatewayCredentialsProvider {
                override suspend fun credentials() = GatewayCredentials(apiKey = "k", senderId = "s")
            },
            baseUrl = "http://127.0.0.1:1/",
        )

        assertThat(blazeTech.checkStatus("txn-123")).isEqualTo(DeliveryStatusOutcome.NotSupported)
    }

    // --- Secrets --------------------------------------------------------------

    @Test
    fun `the API key never appears in a request body`() = runTest {
        // Constraint 7: unlike BlazeTech, HostPinnacle's key travels in a
        // header, not the body — assert it never leaks into the form fields.
        enqueueJson(200, """{"status":"success","statusCode":"200","transactionId":"txn-1"}""")

        gateway.sendSms("0700000000", "hi")

        assertThat(server.takeRequest().body!!.utf8()).doesNotContain(credentials.apiKey)
    }

    private fun SendOutcome.failureReason(): SendFailure {
        assertThat(this).isInstanceOf(SendOutcome.Failed::class.java)
        return (this as SendOutcome.Failed).reason
    }
}
