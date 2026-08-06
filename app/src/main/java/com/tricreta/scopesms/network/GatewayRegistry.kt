package com.tricreta.scopesms.network

/**
 * Resolves a [GatewayProvider] to its [SmsGateway] client.
 *
 * The one place that needs to know both gateways exist at once —
 * [com.tricreta.scopesms.queue.OutboundQueue] uses it to route a job through
 * *its own captured* provider (see [com.tricreta.scopesms.queue.OutboundJob.provider]),
 * and Settings' test-send uses it to send through whichever provider the agent
 * currently has selected. Everything else (each [SmsGateway] instance itself)
 * only ever knows about its own provider's credentials.
 */
class GatewayRegistry(
    private val blazeTech: SmsGateway,
    private val hostPinnacle: SmsGateway,
) {
    fun forProvider(provider: GatewayProvider): SmsGateway = when (provider) {
        GatewayProvider.BLAZETECH -> blazeTech
        GatewayProvider.HOSTPINNACLE -> hostPinnacle
    }
}
