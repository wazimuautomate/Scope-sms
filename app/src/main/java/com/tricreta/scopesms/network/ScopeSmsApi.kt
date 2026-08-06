package com.tricreta.scopesms.network

import androidx.annotation.Keep
import com.squareup.moshi.Json
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit binding for the BlazeTech ("SCOPE SMS API") gateway. Wire format
 * lives here and nowhere else — nothing outside `network/` should know these
 * field names. See `network/HostPinnacleApi.kt` for the other gateway.
 *
 * Base URL: `https://sms.blazetechscope.com/v1/`
 *
 * `POST /bulksms` is deliberately absent. Both reply flows are personalised per
 * recipient (different name, amount, bundle), so a shared-message endpoint
 * can't express them (CLAUDE.md, gateway section).
 */
internal interface ScopeSmsApi {

    /**
     * Returns the raw [Response] rather than the body so the gateway's HTTP
     * status is available to the failure mapping — Retrofit would otherwise
     * throw a generic HttpException and lose the distinction between "retry
     * this" and "the agent must fix their API key".
     */
    @POST("sendsms")
    suspend fun sendSms(@Body request: SendSmsRequest): Response<SendSmsResponse>
}

/**
 * `{ message, phone, sender_id, api_key }`.
 *
 * The API key travels in the body. Do not add an OkHttp logging interceptor at
 * BODY level anywhere in this package — that would write the agent's key to
 * logcat, where any app with log access could read it (constraint 7).
 *
 * `@Keep` is not decoration. Moshi reads these classes reflectively (see
 * [BlazeTechGateway.create]) and the release build runs R8 with
 * `isMinifyEnabled = true`, which would otherwise rename `senderId` to `a` and
 * silently change the JSON the gateway receives. That breaks *only* in release —
 * debug CI stays green — so the keep rule in `proguard-rules.pro` plus this
 * annotation are what stand between a passing pipeline and an agent whose
 * replies all fail in production.
 */
@Keep
internal data class SendSmsRequest(
    @param:Json(name = "message") val message: String,
    @param:Json(name = "phone") val phone: String,
    @param:Json(name = "sender_id") val senderId: String,
    @param:Json(name = "api_key") val apiKey: String,
) {
    /** Keeps the key out of stack traces, crash output and accidental logging. */
    override fun toString(): String =
        "SendSmsRequest(phone=$phone, senderId=$senderId, message=${message.length} chars, apiKey=***)"
}

/**
 * The gateway's response to a send.
 *
 * ## Two shapes, because the docs and the live gateway disagree
 * The docs describe `response-code: 200` + `messageid`. The **live** gateway
 * (verified against the real endpoint, 2026-07) returns neither — a success is:
 * ```
 * {"status":"success","statusCode":"200","reason":"success",
 *  "mobile":"2547…","invalidMobile":"","transactionId":"…","msgId":"","requestTime":"…"}
 * ```
 * Note `statusCode` is a **string** and the id is `transactionId` (`msgId` is
 * usually empty on the immediate response). Reading only the documented fields
 * made every real send look like an unexpected response — which is retryable —
 * so a delivered SMS was sent up to 5 times and then logged failed. Both shapes
 * are modelled so [BlazeTechGateway] can honour whichever the gateway sends.
 *
 * Every field is nullable: this models what the gateway *may* send back,
 * including error bodies, and a missing field must produce a typed failure
 * rather than a Moshi crash inside the worker. `response-code` is hyphenated on
 * the wire — not a typo, and not a valid Kotlin identifier, hence the @Json name.
 *
 * Reused as-is for [HostPinnacleGateway]: verified against the live HostPinnacle
 * endpoint, its send response is byte-for-byte this same shape
 * (`status`/`mobile`/`invalidMobile`/`transactionId`/`statusCode`/`reason`) —
 * not a coincidence, HostPinnacle-family gateways share it.
 */
@Keep
internal data class SendSmsResponse(
    // --- What the live gateway actually returns ---
    @param:Json(name = "status") val status: String? = null,
    @param:Json(name = "statusCode") val statusCode: String? = null,
    @param:Json(name = "reason") val reason: String? = null,
    @param:Json(name = "transactionId") val transactionId: String? = null,
    @param:Json(name = "msgId") val msgId: String? = null,
    /** Non-blank names a recipient the gateway rejected (invalid number). */
    @param:Json(name = "invalidMobile") val invalidMobile: String? = null,
    @param:Json(name = "mobile") val mobile: String? = null,
    // --- Documented shape, kept as a fallback ---
    @param:Json(name = "response-code") val responseCode: Int? = null,
    @param:Json(name = "messageid") val messageId: String? = null,
    @param:Json(name = "networkid") val networkId: String? = null,
    /** Present on documented error bodies (invalid api key, insufficient balance, …). */
    @param:Json(name = "message") val message: String? = null,
)
