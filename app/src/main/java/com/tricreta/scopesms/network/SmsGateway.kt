package com.tricreta.scopesms.network

/**
 * The contract both gateway clients ([BlazeTechGateway], [HostPinnacleGateway])
 * implement, so [com.tricreta.scopesms.queue.OutboundQueue] and Settings' test
 * send can talk to "whichever provider is relevant" without knowing which one.
 *
 * Everything either implementation can do to us — a 429, a dead socket, an
 * unregistered sender ID, an undocumented body — comes back as a [SendOutcome],
 * never an exception. See `network/README.md`.
 */
interface SmsGateway {

    /**
     * Sends one personalised message.
     *
     * @param phone recipient in any Kenyan format; normalised to the
     *   implementation's own wire format before sending.
     * @param message the rendered template body (Phase 4).
     * @param senderId the ID to send under, or null to use whatever is
     *   currently stored for this gateway's own account. See
     *   [BlazeTechGateway.sendSms]'s doc for the full argument for why the
     *   queue always passes the job's captured ID rather than leaving this null.
     */
    suspend fun sendSms(
        phone: String,
        message: String,
        senderId: String? = null,
    ): SendOutcome

    /**
     * Looks up delivery status for a message this gateway previously accepted
     * — [messageId] is the id [sendSms] returned in [SendOutcome.Sent].
     *
     * Default-implemented as [DeliveryStatusOutcome.NotSupported] so
     * [BlazeTechGateway] needs zero changes: BlazeTech's optional `/smsstatus`
     * endpoint is undocumented/unimplemented (CLAUDE.md, gateway section) —
     * "not supported" is the honest answer there, not a made-up one.
     * [HostPinnacleGateway] overrides this for real.
     */
    suspend fun checkStatus(messageId: String): DeliveryStatusOutcome = DeliveryStatusOutcome.NotSupported
}

/**
 * The outcome of one delivery-status lookup — deliberately separate from
 * [SendOutcome], which is about *accepting* a send, not what happened to it
 * afterwards.
 */
sealed interface DeliveryStatusOutcome {

    /**
     * A real status was found. [status] is the gateway's own word (e.g.
     * `"DELIVERED"`, `"FAILED"`) — surfaced verbatim rather than mapped to an
     * enum, since the full vocabulary isn't documented and a mis-guessed
     * mapping would silently hide values this app has never seen.
     */
    data class Known(val status: String, val cause: String?) : DeliveryStatusOutcome

    /** This gateway doesn't implement status lookup at all. */
    data object NotSupported : DeliveryStatusOutcome

    /**
     * Checked, but the gateway/network didn't give a usable answer — not
     * found (yet), unreachable, or an unparseable response. [reason] is
     * agent-readable, mirroring [SendFailure.description].
     */
    data class Failed(val reason: String) : DeliveryStatusOutcome
}
