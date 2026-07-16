package com.scopesms.autoreply.network

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
     */
    suspend fun sendSms(phone: String, message: String): SendOutcome {
        val credentials = credentialsProvider.credentials()
            ?: return SendOutcome.Failed(SendFailure.InvalidApiKey)

        val localPhone = PhoneNumbers.toLocalFormat(phone)
            ?: return SendOutcome.Failed(SendFailure.InvalidPhone(phone))

        val request = SendSmsRequest(
            message = message,
            phone = localPhone,
            senderId = credentials.senderId,
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
            // A 200 does not by itself mean "sent" — the gateway carries its own
            // response-code in the body, and the documented error bodies (bad
            // key, no balance) can arrive under a 200. Trusting the HTTP status
            // alone would mark those jobs SENT and lose the message silently.
            val gatewayMessage = body?.message
            body?.let { classifyErrorMessage(gatewayMessage) }?.let {
                return SendOutcome.Failed(it)
            }

            val messageId = body?.messageId
            return when {
                body == null ->
                    SendOutcome.Failed(SendFailure.Unexpected("empty body on HTTP 200"))

                body.responseCode != null && body.responseCode != SUCCESS_CODE ->
                    SendOutcome.Failed(
                        SendFailure.Unexpected(
                            "response-code ${body.responseCode}" +
                                (gatewayMessage?.let { ": $it" } ?: ""),
                        ),
                    )

                // Success without an id: we cannot record what was sent, and we
                // must not retry (it may well have gone out and a retry would
                // charge the agent twice for a second copy to the customer).
                messageId.isNullOrBlank() ->
                    SendOutcome.Failed(SendFailure.Unexpected("success with no messageid"))

                else -> SendOutcome.Sent(
                    messageId = messageId,
                    mobile = body.mobile,
                    networkId = body.networkId,
                )
            }
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
