package com.tricreta.scopesms.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The app's one way to send an SMS. Phase 5.
 *
 * Everything the gateway can do to us — a 429, a dead socket, an unregistered
 * sender ID, an undocumented body — comes back as a [SendOutcome], never an
 * exception. The Phase 5b worker's job is to react to [SendFailure.retryable],
 * and it can only do that if this class refuses to throw.
 *
 * This runs inside a WorkManager worker, never the SMS receiver: a slow call
 * here must never delay ingestion (CLAUDE.md constraint 5).
 */
class ScopeSmsGateway internal constructor(
    private val api: ScopeSmsApi,
    private val credentialsProvider: GatewayCredentialsProvider,
) {

    /**
     * Sends one personalised message.
     *
     * @param phone recipient in any Kenyan format; normalised before sending.
     * @param message the rendered template body (Phase 4).
     * @param senderId the ID to send under, or null to use whatever is currently
     *   stored.
     *
     *   The queue passes the ID captured when the job was created
     *   ([com.tricreta.scopesms.queue.OutboundJob.senderId]), and that is the
     *   whole reason this parameter exists. Re-reading it here would mean a reply
     *   queued while the agent was offline goes out under an ID they changed in
     *   the meantime — and if the new one isn't registered with SCOPE yet, every
     *   queued reply fails terminally on `UnregisteredSenderId` when they would
     *   have sent fine under the original.
     *
     *   The **API key is deliberately still read live**: it identifies the
     *   account, and a key the agent has replaced is simply invalid — there is
     *   nothing to be gained by sending with a stale one.
     */
    suspend fun sendSms(
        phone: String,
        message: String,
        senderId: String? = null,
    ): SendOutcome {
        val credentials = credentialsProvider.credentials()
            ?: return SendOutcome.Failed(SendFailure.InvalidApiKey)

        val localPhone = PhoneNumbers.toLocalFormat(phone)
            ?: return SendOutcome.Failed(SendFailure.InvalidPhone(phone))

        val request = SendSmsRequest(
            message = message,
            phone = localPhone,
            senderId = senderId ?: credentials.senderId,
            apiKey = credentials.apiKey,
        )

        return try {
            interpret(api.sendSms(request))
        } catch (e: SocketTimeoutException) {
            SendOutcome.Failed(SendFailure.Timeout)
        } catch (e: IOException) {
            // OkHttp reports "no data connection", DNS failure and a dropped
            // socket all as IOException. They're the same thing to the queue:
            // hold the job and try again when the network is back.
            SendOutcome.Failed(SendFailure.NoConnectivity)
        } catch (e: Exception) {
            // A Moshi parse error on an undocumented body would otherwise
            // escape into the worker and be reported as a generic crash.
            SendOutcome.Failed(SendFailure.Unexpected(e.javaClass.simpleName))
        }
    }

    private fun interpret(response: retrofit2.Response<SendSmsResponse>): SendOutcome {
        val body = response.body()

        if (response.isSuccessful) {
            if (body == null) {
                return SendOutcome.Failed(SendFailure.Unexpected("empty body on HTTP 200"))
            }

            // The trackable id. The live gateway returns it as transactionId
            // (msgId is usually empty on the immediate response, filled once the
            // message is dispatched); the documented format called it messageid.
            // First non-blank, so the activity log has something to show.
            val trackingId = listOfNotNull(body.msgId, body.transactionId, body.messageId)
                .firstOrNull { it.isNotBlank() }
            val gatewayText = body.reason ?: body.message

            // A positive delivery signal is authoritative and is checked FIRST.
            //
            // The LIVE gateway signals success with {"status":"success",
            // "statusCode":"200"} — NOT the documented response-code/messageid
            // (verified against the real endpoint). Reading only the documented
            // fields left every real send looking like an "unexpected response",
            // which is retryable — so a delivered SMS was sent up to 5 times and
            // then logged failed. The agent confirmed all 5 on the SCOPE
            // dashboard. Believe any of these: a success word, a 200 (string or
            // int), or a real id. Delivery beats prose.
            val delivered =
                body.status.equals("success", ignoreCase = true) ||
                    body.statusCode == SUCCESS_CODE_TEXT ||
                    body.responseCode == SUCCESS_CODE ||
                    trackingId != null
            if (delivered) {
                return SendOutcome.Sent(
                    // Blank when the gateway confirmed via status/code but gave no
                    // id yet. It must not be retried (a retry double-charges the
                    // agent and double-texts the customer) and must not be failed.
                    messageId = trackingId.orEmpty(),
                    mobile = body.mobile,
                    networkId = body.networkId,
                )
            }

            // Not delivered. A single send that names a rejected number in
            // invalidMobile is a terminal InvalidPhone; otherwise the gateway's
            // error text (documented bad-key / no-balance bodies) is a real error.
            if (!body.invalidMobile.isNullOrBlank()) {
                return SendOutcome.Failed(SendFailure.InvalidPhone(body.invalidMobile.orEmpty()))
            }
            classifyErrorMessage(gatewayText)?.let { return SendOutcome.Failed(it) }

            return SendOutcome.Failed(
                SendFailure.Unexpected(
                    "no success signal" + (gatewayText?.let { ": $it" } ?: ""),
                ),
            )
        }

        // Non-2xx. Prefer the gateway's own error text where it gives one — it
        // distinguishes cases that share a status code.
        val errorMessage = readErrorMessage(response)
        classifyErrorMessage(errorMessage)?.let { return SendOutcome.Failed(it) }

        return SendOutcome.Failed(
            when (val code = response.code()) {
                401 -> SendFailure.InvalidApiKey
                403 -> SendFailure.UnregisteredSenderId
                429 -> SendFailure.RateLimited
                in 500..599 -> SendFailure.ServerError(code)
                else -> SendFailure.Rejected(code, errorMessage)
            },
        )
    }

    /**
     * Maps the gateway's documented error *text* to a typed reason.
     *
     * The wire contract we were given documents these as message strings rather
     * than distinct codes, so matching on text is the only signal available.
     * That's brittle by nature — a wording change upstream silently drops a case
     * back to its status-code default. Matching is therefore loose (lowercased,
     * substring) and every unmatched case still lands on a typed status-code
     * failure rather than falling through to "unknown".
     */
    private fun classifyErrorMessage(message: String?): SendFailure? {
        val text = message?.lowercase()?.trim() ?: return null
        return when {
            text.contains("api key") || text.contains("apikey") -> SendFailure.InvalidApiKey
            text.contains("sender") -> SendFailure.UnregisteredSenderId
            text.contains("balance") || text.contains("insufficient") ->
                SendFailure.InsufficientBalance
            text.contains("phone") || text.contains("mobile") || text.contains("number") ->
                SendFailure.InvalidPhone(text)
            else -> null
        }
    }

    private fun readErrorMessage(response: retrofit2.Response<SendSmsResponse>): String? = try {
        // errorBody is a one-shot stream; read it once, defensively.
        response.errorBody()?.string()?.takeIf { it.isNotBlank() }
    } catch (e: IOException) {
        null
    }

    companion object {
        private const val SUCCESS_CODE = 200

        /** The live gateway sends its success code as the string `"200"`, not an int. */
        private const val SUCCESS_CODE_TEXT = "200"

        const val BASE_URL = "https://sms.blazetechscope.com/v1/"

        /**
         * Docs recommend 30–60s. We take the upper end: this call only ever runs
         * in a background worker, so a slow gateway costs nothing but a held
         * coroutine, whereas a premature timeout costs a retry — and on a rural
         * 2G/3G connection, slow is the normal case, not the failure case.
         */
        private const val TIMEOUT_SECONDS = 60L

        /**
         * @param baseUrl overridden by tests to point at MockWebServer.
         */
        fun create(
            credentialsProvider: GatewayCredentialsProvider,
            baseUrl: String = BASE_URL,
            client: OkHttpClient = defaultClient(),
        ): ScopeSmsGateway {
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            return ScopeSmsGateway(retrofit.create(ScopeSmsApi::class.java), credentialsProvider)
        }

        /**
         * No logging interceptor, deliberately. The API key travels in the
         * request body, so a BODY-level interceptor would print it to logcat —
         * the single easiest way to leak it (constraint 7, and `network/README.md`
         * calls this out by name). Retries are the queue's job, so OkHttp's own
         * retry is left off to keep attempt counting in one place.
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
