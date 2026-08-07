package com.tricreta.scopesms.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * HostPinnacle — the client's second, independently-selectable SMS gateway
 * alongside [BlazeTechGateway] (CLAUDE.md, "SMS Gateway Integration"). Added
 * without touching BlazeTech: it is live in production and must keep working
 * exactly as it does today (CLAUDE.md constraint 9).
 *
 * Same shape as [BlazeTechGateway] — a thin, non-throwing wrapper around one
 * Retrofit call, sharing [SendSmsResponseInterpreter] because the two gateways'
 * responses are verified to be byte-for-byte the same shape. The two real
 * differences are the request encoding (form fields here, JSON for BlazeTech)
 * and auth (`userid`+`password` body fields here — see [HostPinnacleApi]'s doc
 * for why not the `apikey` header — vs. an in-body key field for BlazeTech) —
 * both confined to [HostPinnacleApi] and this class.
 *
 * This runs inside a WorkManager worker, never the SMS receiver: a slow call
 * here must never delay ingestion (CLAUDE.md constraint 5).
 */
class HostPinnacleGateway internal constructor(
    private val api: HostPinnacleApi,
    private val credentialsProvider: GatewayCredentialsProvider,
) : SmsGateway {

    /**
     * Sends one personalised message. See [BlazeTechGateway.sendSms] for the
     * full argument on why [senderId] is read from the queued job rather than
     * live settings, and why the API key is still read live — identical
     * reasoning applies here, just against this gateway's own stored account.
     */
    override suspend fun sendSms(
        phone: String,
        message: String,
        senderId: String?,
    ): SendOutcome {
        val credentials = credentialsProvider.credentials()
            ?: return SendOutcome.Failed(SendFailure.InvalidApiKey)
        val userId = credentials.userId
            ?: return SendOutcome.Failed(SendFailure.InvalidApiKey)

        val internationalPhone = PhoneNumbers.toInternationalFormat(phone)
            ?: return SendOutcome.Failed(SendFailure.InvalidPhone(phone))

        return try {
            interpret(
                api.sendSms(
                    userId = userId,
                    password = credentials.apiKey,
                    mobile = internationalPhone,
                    message = message,
                    senderId = senderId ?: credentials.senderId,
                ),
            )
        } catch (e: SocketTimeoutException) {
            SendOutcome.Failed(SendFailure.Timeout)
        } catch (e: IOException) {
            // Same treatment as BlazeTechGateway: no data connection, DNS
            // failure and a dropped socket are all "try again later" to the
            // queue, not a crash.
            SendOutcome.Failed(SendFailure.NoConnectivity)
        } catch (e: Exception) {
            // A Moshi parse error on an undocumented body would otherwise
            // escape into the worker and be reported as a generic crash.
            SendOutcome.Failed(SendFailure.Unexpected(e.javaClass.simpleName))
        }
    }

    /**
     * Delivery-status lookup via `reports/status`, HostPinnacle's own
     * documented endpoint (BlazeTech has no equivalent this app implements —
     * see [SmsGateway.checkStatus]'s default).
     *
     * Same guard order as [sendSms]: missing credentials fail terminally
     * without a network call.
     */
    override suspend fun checkStatus(messageId: String): DeliveryStatusOutcome {
        val credentials = credentialsProvider.credentials()
            ?: return DeliveryStatusOutcome.Failed("Gateway not set up")
        val userId = credentials.userId
            ?: return DeliveryStatusOutcome.Failed("Gateway not set up")

        val today = LocalDate.now()
        return try {
            interpretStatus(
                api.checkStatus(
                    userId = userId,
                    password = credentials.apiKey,
                    uuid = messageId,
                    // A generous few-day window: this is checked shortly after
                    // a real send, not run as a historical report.
                    fromDate = today.minusDays(STATUS_LOOKBACK_DAYS).format(DATE_FORMAT),
                    toDate = today.format(DATE_FORMAT),
                ),
            )
        } catch (e: IOException) {
            DeliveryStatusOutcome.Failed("Could not reach the gateway")
        } catch (e: Exception) {
            DeliveryStatusOutcome.Failed("Unexpected gateway response: ${e.javaClass.simpleName}")
        }
    }

    private fun interpretStatus(response: retrofit2.Response<StatusCheckResponse>): DeliveryStatusOutcome {
        if (!response.isSuccessful) {
            return DeliveryStatusOutcome.Failed("Gateway rejected the status request (HTTP ${response.code()})")
        }
        val detail = response.body()?.response?.reportStatusList?.firstOrNull()?.status
            ?: return DeliveryStatusOutcome.Failed("No status found for this message yet")

        return DeliveryStatusOutcome.Known(status = detail.status ?: "UNKNOWN", cause = detail.cause)
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
        /** `reports/status`'s `fromdate`/`todate` fields are plain `YYYY-MM-DD`. */
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        private const val STATUS_LOOKBACK_DAYS = 2L

        const val BASE_URL = "https://smsportal.hostpinnacle.co.ke/SMSApi/"

        /** Same rationale as [BlazeTechGateway]'s: this only ever runs in the background. */
        private const val TIMEOUT_SECONDS = 60L

        /**
         * @param baseUrl overridden by tests to point at MockWebServer.
         */
        fun create(
            credentialsProvider: GatewayCredentialsProvider,
            baseUrl: String = BASE_URL,
            client: OkHttpClient = defaultClient(),
        ): HostPinnacleGateway {
            // A converter factory is still needed even though the request is
            // form-encoded (@Field bypasses it): the response body is JSON, and
            // this is what deserialises it into SendSmsResponse.
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            return HostPinnacleGateway(retrofit.create(HostPinnacleApi::class.java), credentialsProvider)
        }

        /**
         * No logging interceptor, deliberately. The form body carries the
         * password and the message text — either would land in logcat under a
         * BODY-level interceptor (constraint 7).
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
