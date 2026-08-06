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
 * BlazeTech ("SCOPE SMS API") — the app's original gateway, live in production.
 * Phase 5, then made one of two selectable providers alongside
 * [HostPinnacleGateway] (CLAUDE.md, "SMS Gateway Integration").
 *
 * **Renamed from `ScopeSmsGateway`, behaviour unchanged.** Was the app's one and
 * only gateway client; is now one [SmsGateway] implementation the agent can pick
 * in Settings. Every existing agent must see zero difference until they
 * deliberately switch — see [GatewayProvider.DEFAULT].
 *
 * Everything the gateway can do to us — a 429, a dead socket, an unregistered
 * sender ID, an undocumented body — comes back as a [SendOutcome], never an
 * exception. The Phase 5b worker's job is to react to [SendFailure.retryable],
 * and it can only do that if this class refuses to throw.
 *
 * This runs inside a WorkManager worker, never the SMS receiver: a slow call
 * here must never delay ingestion (CLAUDE.md constraint 5).
 */
class BlazeTechGateway internal constructor(
    private val api: ScopeSmsApi,
    private val credentialsProvider: GatewayCredentialsProvider,
) : SmsGateway {

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
    override suspend fun sendSms(
        phone: String,
        message: String,
        senderId: String?,
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

    private fun interpret(response: retrofit2.Response<SendSmsResponse>): SendOutcome =
        SendSmsResponseInterpreter.interpret(
            httpCode = response.code(),
            isSuccessful = response.isSuccessful,
            body = response.body(),
            errorBody = if (response.isSuccessful) null else readErrorMessage(response),
        )

    private fun readErrorMessage(response: retrofit2.Response<SendSmsResponse>): String? = try {
        // errorBody is a one-shot stream; read it once, defensively.
        response.errorBody()?.string()?.takeIf { it.isNotBlank() }
    } catch (e: IOException) {
        null
    }

    companion object {
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
        ): BlazeTechGateway {
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            return BlazeTechGateway(retrofit.create(ScopeSmsApi::class.java), credentialsProvider)
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
