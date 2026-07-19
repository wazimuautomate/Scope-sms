package com.tricreta.scopesms.network

/**
 * The outcome of one `POST /sendsms` attempt.
 *
 * Phase 5. See `network/README.md`.
 */
sealed interface SendOutcome {

    /** The gateway accepted the message and gave us an id to track it by. */
    data class Sent(
        val messageId: String,
        /** The number the gateway says it delivered to, in its own format. */
        val mobile: String?,
        val networkId: String?,
    ) : SendOutcome

    data class Failed(val reason: SendFailure) : SendOutcome
}

/**
 * Why a send attempt failed, in terms the agent can act on.
 *
 * `network/README.md`: *"'Send failed' is not an acceptable log line — the agent
 * has to be able to diagnose it."* Every documented gateway error maps to its
 * own case here, and each one carries [retryable], which is the property the
 * Phase 5b queue actually branches on.
 *
 * **[retryable] is the load-bearing bit.** Retrying a 429 or a dropped
 * connection is correct — the message goes out a moment later and the customer
 * is none the wiser. Retrying a bad API key or an unregistered sender ID is
 * not: it will fail identically every time, and the queue would burn its
 * attempts and the agent's time against a wall. Those must surface in Settings
 * instead (CLAUDE.md, gateway section).
 *
 * **Note (2026-07-19, send-once policy):** the queue no longer auto-retries
 * anything — the client was being re-billed for retried sends. So [retryable]
 * no longer gates a re-send; every failure is terminal and shown in the activity
 * log, and recovery is a deliberate manual Force-send. The flag is kept as an
 * honest classification of the failure, not as a retry switch.
 */
sealed interface SendFailure {

    /**
     * Whether trying the exact same request again could plausibly succeed.
     *
     * Terminal failures need the agent (or SCOPE) to change something first.
     */
    val retryable: Boolean

    /** A human-readable reason, shown in the activity log. Never contains the API key. */
    val description: String

    // --- Terminal: retrying changes nothing --------------------------------

    /**
     * HTTP 401 / "invalid api key". The key is wrong, revoked, or was never
     * entered. Every subsequent send fails identically until the agent fixes it
     * in Settings, so the queue must stop rather than retry.
     */
    data object InvalidApiKey : SendFailure {
        override val retryable = false
        override val description = "Invalid API key — re-enter it in Settings"
    }

    /**
     * The sender ID isn't registered with SCOPE for this account.
     *
     * CLAUDE.md is explicit that this is an account-setup prerequisite on the
     * client's side and *not something the app can fix* — so it must surface a
     * clear error "rather than retrying forever".
     */
    data object UnregisteredSenderId : SendFailure {
        override val retryable = false
        override val description =
            "Sender ID not registered with SCOPE — it must be approved on the gateway account"
    }

    /**
     * The recipient number was rejected. Terminal because the number came from
     * the M-Pesa SMS itself and won't change on retry — a real one usually means
     * the parser (Phase 2) mis-read the sender, which is worth seeing in the log.
     */
    data class InvalidPhone(val phone: String) : SendFailure {
        override val retryable = false
        override val description = "Gateway rejected the recipient number ($phone)"
    }

    /** HTTP 400, or a documented error body we recognise but can't retry. */
    data class Rejected(val httpCode: Int, val gatewayMessage: String?) : SendFailure {
        override val retryable = false
        override val description =
            "Gateway rejected the request (HTTP $httpCode)" +
                (gatewayMessage?.let { ": $it" } ?: "")
    }

    // --- Retryable: the same request may well work shortly -----------------

    /**
     * The account is out of SMS credit.
     *
     * Classified **retryable on purpose**, and it's the one judgement call in
     * this taxonomy. Nothing the app sends will succeed until the agent tops
     * up — which argues terminal. But unlike a bad key, no configuration is
     * wrong, the top-up is a routine thing the agent does from their phone in
     * under a minute, and the customer on the other end is still waiting for
     * their prices. Bounded retries with backoff cover the realistic case
     * ("agent tops up shortly"); if they run out, the job lands in FAILED with
     * this description, which is exactly the signal the agent needs.
     *
     * Revisit if the agent reports the queue thrashing on empty balance.
     */
    data object InsufficientBalance : SendFailure {
        override val retryable = true
        override val description = "SCOPE SMS account is out of balance — top up to resume sending"
    }

    /** HTTP 429. Gateway limit is 100 req/min per API key. Back off, don't drop. */
    data object RateLimited : SendFailure {
        override val retryable = true
        override val description = "Gateway rate limit reached (100/min)"
    }

    /** HTTP 5xx. The gateway's problem, and usually temporary. */
    data class ServerError(val httpCode: Int) : SendFailure {
        override val retryable = true
        override val description = "Gateway server error (HTTP $httpCode)"
    }

    /**
     * No data connection, DNS failure, or the socket dropped.
     *
     * The expected case, not an exceptional one: CLAUDE.md constraint 2 says to
     * design for the phone having no data *at the moment* a payment lands.
     */
    data object NoConnectivity : SendFailure {
        override val retryable = true
        override val description = "No internet connection when the app tried to send"
    }

    /** The request exceeded the client timeout. */
    data object Timeout : SendFailure {
        override val retryable = true
        override val description = "Gateway did not respond in time"
    }

    /**
     * Anything we didn't anticipate — an undocumented response shape, an
     * unparseable body, an unmapped status.
     *
     * Retryable by design: given the choice between dropping the agent's
     * customer message on an unknown response and sending it twice, a bounded
     * retry is the safer default. The dedupe guard in Phase 5b is on
     * transactionCode, so a genuine duplicate is caught there.
     */
    data class Unexpected(val detail: String) : SendFailure {
        override val retryable = true
        override val description = "Unexpected gateway response: $detail"
    }
}
