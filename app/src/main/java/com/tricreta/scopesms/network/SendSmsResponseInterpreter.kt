package com.tricreta.scopesms.network

/**
 * Turns one HTTP response into a [SendOutcome] — shared by [BlazeTechGateway]
 * and [HostPinnacleGateway] because, verified against both live endpoints, they
 * answer with byte-for-byte the same response shape ([SendSmsResponse]) and the
 * same success/failure signalling. Extracted here so that fact only has to be
 * encoded once.
 *
 * ## A positive delivery signal is authoritative and is checked FIRST
 * The LIVE gateway (BlazeTech, and HostPinnacle-family gateways share this
 * shape — see `network/HostPinnacleGateway.kt`) signals success with
 * `{"status":"success","statusCode":"200"}` — NOT the documented
 * response-code/messageid (verified against the real endpoint). Reading only
 * the documented fields left every real send looking like an "unexpected
 * response", which is retryable — so a delivered SMS was sent up to 5 times and
 * then logged failed. The agent confirmed all 5 on the SCOPE dashboard. Believe
 * any of these: a success word, a 200 (string or int), or a real id. Delivery
 * beats prose.
 *
 * Pure Kotlin, no Retrofit/OkHttp types, so it's cheap to unit test and cheap
 * for a caller to drive with fixtures rather than a real HTTP response.
 */
internal object SendSmsResponseInterpreter {

    private const val SUCCESS_CODE = 200

    /** The live gateway sends its success code as the string `"200"`, not an int. */
    private const val SUCCESS_CODE_TEXT = "200"

    /**
     * @param httpCode the response's HTTP status code.
     * @param isSuccessful whether the HTTP layer considered this a 2xx response
     *   (i.e. `retrofit2.Response.isSuccessful`) — kept separate from [httpCode]
     *   rather than re-derived, so a caller's own definition of "successful"
     *   (Retrofit's) is authoritative rather than guessed at here.
     * @param body the parsed response body, when the HTTP layer produced one.
     * @param errorBody the raw HTTP error body text, when [isSuccessful] is
     *   false and the gateway sent one. Reading it is transport-specific
     *   (one-shot stream, may throw) so callers do it and hand over the string.
     */
    fun interpret(
        httpCode: Int,
        isSuccessful: Boolean,
        body: SendSmsResponse?,
        errorBody: String?,
    ): SendOutcome {
        if (isSuccessful) {
            if (body == null) {
                return SendOutcome.Failed(SendFailure.Unexpected("empty body on HTTP $httpCode"))
            }

            // The trackable id. The live gateway returns it as transactionId
            // (msgId is usually empty on the immediate response, filled once the
            // message is dispatched); the documented format called it messageid.
            // First non-blank, so the activity log has something to show.
            val trackingId = listOfNotNull(body.msgId, body.transactionId, body.messageId)
                .firstOrNull { it.isNotBlank() }
            val gatewayText = body.reason ?: body.message

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
        classifyErrorMessage(errorBody)?.let { return SendOutcome.Failed(it) }

        return SendOutcome.Failed(
            when (httpCode) {
                401 -> SendFailure.InvalidApiKey
                403 -> SendFailure.UnregisteredSenderId
                429 -> SendFailure.RateLimited
                in 500..599 -> SendFailure.ServerError(httpCode)
                else -> SendFailure.Rejected(httpCode, errorBody)
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
     * failure rather than falling through to "unknown". Deliberately
     * vendor-agnostic — both BlazeTech and HostPinnacle share it unchanged.
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
}
