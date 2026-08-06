package com.tricreta.scopesms.network

import androidx.annotation.Keep
import com.squareup.moshi.Json
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit binding for the HostPinnacle gateway — the client's second,
 * independently-selectable SMS provider alongside BlazeTech
 * ([ScopeSmsApi]/[BlazeTechGateway]). Wire format lives here and nowhere
 * else — nothing outside `network/` should know these field names.
 *
 * Base URL: `https://smsportal.hostpinnacle.co.ke/SMSApi/`
 *
 * **The single biggest difference from BlazeTech**: this endpoint takes
 * `application/x-www-form-urlencoded` fields, not a JSON body. Auth is the
 * `apikey` HTTP header — this app only ever uses HostPinnacle's apikey auth
 * mode, never its userid/password mode, so no fields for that exist here.
 *
 * `mobile` is **international format with a country code and no leading `+`**
 * (e.g. `254712345678`) — the opposite of BlazeTech's local `07XXXXXXXX`, hence
 * [PhoneNumbers.toInternationalFormat] rather than [PhoneNumbers.toLocalFormat].
 *
 * The response shape ([SendSmsResponse]) is verified identical to BlazeTech's
 * live shape — see that class's doc for the field-by-field proof.
 */
internal interface HostPinnacleApi {

    /**
     * Returns the raw [Response] rather than the body so the gateway's HTTP
     * status is available to [SendSmsResponseInterpreter] — same reasoning as
     * [ScopeSmsApi.sendSms].
     */
    @FormUrlEncoded
    @POST("send")
    suspend fun sendSms(
        @Header("apikey") apiKey: String,
        @Field("mobile") mobile: String,
        @Field("msg") message: String,
        @Field("senderid") senderId: String,
        @Field("sendMethod") sendMethod: String = "quick",
        @Field("msgType") msgType: String = "text",
        @Field("duplicatecheck") duplicateCheck: String = "true",
        @Field("output") output: String = "json",
    ): Response<SendSmsResponse>

    /**
     * Delivery-status lookup by transaction id — [HostPinnacleGateway]'s
     * [SmsGateway.checkStatus]. [uuid] is the `transactionId` [sendSms]'s
     * response carried back as [SendSmsResponse.transactionId] (surfaced to
     * callers as [SendOutcome.Sent.messageId]).
     *
     * [fromDate]/[toDate] are `YYYY-MM-DD`; [HostPinnacleGateway] passes a
     * generous few-day window since this is checked shortly after a real send,
     * not as a historical report.
     */
    @FormUrlEncoded
    @POST("reports/status")
    suspend fun checkStatus(
        @Header("apikey") apiKey: String,
        @Field("uuid") uuid: String,
        @Field("fromdate") fromDate: String,
        @Field("todate") toDate: String,
        @Field("output") output: String = "json",
    ): Response<StatusCheckResponse>
}

/**
 * `reports/status`'s response, nested three levels deep:
 * `{"response":{"report_statusList":[{"status":{"uuId":...,"Status":...,"Cause":...}}]}}`.
 *
 * Every level is nullable/optional, same defensive style as [SendSmsResponse] —
 * a missing or malformed field must produce a typed [DeliveryStatusOutcome.Failed],
 * never crash the caller. Only the fields [HostPinnacleGateway] actually reads
 * are modelled; the response carries other, space-named keys (`"Channel Name"`
 * etc.) this app has no use for.
 */
@Keep
internal data class StatusCheckResponse(
    @param:Json(name = "response") val response: StatusCheckResponseBody? = null,
)

@Keep
internal data class StatusCheckResponseBody(
    @param:Json(name = "status") val status: String? = null,
    @param:Json(name = "report_statusList") val reportStatusList: List<StatusReportEntry>? = null,
)

@Keep
internal data class StatusReportEntry(
    @param:Json(name = "status") val status: StatusDetail? = null,
)

/** The wire keys are capitalised (`Status`/`Cause`) — not a typo, the live API's own casing. */
@Keep
internal data class StatusDetail(
    @param:Json(name = "uuId") val uuId: String? = null,
    @param:Json(name = "Status") val status: String? = null,
    @param:Json(name = "Cause") val cause: String? = null,
)
