package com.scopesms.autoreply.domain.templates

import com.scopesms.autoreply.domain.cache.SnapshotCache

/**
 * Both reply bodies, indexed by flow, with defaults filled in for any the agent
 * hasn't customised.
 *
 * [forType] is total — there is always a template for both flows, so the decide
 * path can't be left holding null and improvising a message.
 */
class TemplateSnapshot private constructor(
    private val byType: Map<TemplateType, MessageTemplate>,
) {

    /** Never null: falls back to [DefaultTemplates] when nothing is stored. */
    fun forType(type: TemplateType): MessageTemplate = byType.getValue(type)

    /** True when neither flow's body has been edited by the agent. */
    val isAllDefault: Boolean get() = byType.values.all { it.isDefault }

    companion object {

        /** What a fresh install renders with, before Room has anything stored. */
        val DEFAULTS: TemplateSnapshot = from(emptyList())

        /**
         * Overlays [stored] onto the defaults.
         *
         * Room holds at most one row per type, and may hold none — a fresh
         * install has an empty table. Filling the gaps here rather than seeding
         * the database keeps "what the agent wrote" and "what we ship" cleanly
         * apart: there's no migration to write when the default wording is
         * improved, and no risk of a seed row being mistaken for the agent's own
         * text. See [DefaultTemplates] for why templates get defaults at all
         * when pricing rules deliberately don't.
         */
        fun from(stored: List<MessageTemplate>): TemplateSnapshot = TemplateSnapshot(
            TemplateType.entries.associateWith { type ->
                stored.firstOrNull { it.type == type } ?: DefaultTemplates.templateFor(type)
            },
        )
    }
}

/**
 * The in-memory template pair the SMS receive path renders from.
 *
 * Fed from Room by `di/AppContainer`; see [SnapshotCache] for the contract.
 *
 * Unlike [com.scopesms.autoreply.domain.rules.RuleCache], reading this before
 * the first load wouldn't send a *wrong* message — the defaults are sane — but
 * it would send the shipped wording instead of the agent's own, which for
 * someone who has carefully worded their customer replies is still a bug worth
 * closing. Hence the same `awaitLoaded()` discipline.
 */
class TemplateCache : SnapshotCache<List<MessageTemplate>, TemplateSnapshot>() {

    override fun buildSnapshot(source: List<MessageTemplate>): TemplateSnapshot =
        TemplateSnapshot.from(source)
}
