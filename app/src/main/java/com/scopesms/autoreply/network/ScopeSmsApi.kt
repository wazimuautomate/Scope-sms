package com.scopesms.autoreply.network

import androidx.annotation.Keep
import com.squareup.moshi.Json
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit binding for the SCOPE SMS gateway. Wire format lives here and
 * nowhere else — nothing outside `network/` should know these field names.
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
 * [ScopeSmsGateway.create]) and the release build runs R8 with
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
 * Documented success shape: `response-code: 200` with `messageid`.
 *
 * Note `response-code` is hyphenated on the wire — it is not a typo and it
 * cannot be a Kotlin identifier, hence the explicit @Json name.
 *
 * Every field is nullable: this models what the gateway *may* send back,
 * including error bodies, and a missing field must produce a typed failure
 * rather than a Moshi crash inside the worker.
 */
@Keep
internal data class SendSmsResponse(
    @param:Json(name = "response-code") val responseCode: Int?,
    @param:Json(name = "messageid") val messageId: String?,
    @param:Json(name = "mobile") val mobile: String?,
    @param:Json(name = "networkid") val networkId: String?,
    /** Present on documented error bodies (invalid api key, insufficient balance, …). */
    @param:Json(name = "message") val message: String?,
)
