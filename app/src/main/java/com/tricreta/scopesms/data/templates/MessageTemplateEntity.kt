package com.tricreta.scopesms.data.templates

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tricreta.scopesms.domain.templates.MessageTemplate
import com.tricreta.scopesms.domain.templates.TemplateType

/**
 * The agent's custom wording for one flow.
 *
 * ## Deviation from BUILD-PLAN — please read before extending
 * The plan specifies `MessageTemplate(id, type, body, isDefault)`. This table
 * drops `id` and makes `type` the primary key, so at most one row can exist per
 * flow.
 *
 * The reason is that `id` only earns its place if several templates can share a
 * type, and then `isDefault` has to pick the live one. Nothing in the app wants
 * that: the Phase 7 Templates screen is two editors, one per flow, and the
 * decide path asks for "the unmatched template" expecting one answer. Carrying
 * the id anyway would let the table hold three UNMATCHED rows with `isDefault`
 * true on two of them — a state with no meaning that every reader would have to
 * defend against. A primary key on `type` makes it unrepresentable in SQLite.
 *
 * If a later phase genuinely needs template variants (A/B wording, per-time-of-
 * day copy), this becomes `(id, type, isDefault)` with a real migration — a
 * deliberate change, not an accident that snuck in through a nullable field.
 *
 * ## Why `isDefault` isn't a column
 * A row exists only when the agent has customised that flow. "Still default" is
 * therefore "no row", not a flag that could contradict the body next to it. The
 * default text lives in code
 * ([com.tricreta.scopesms.domain.templates.DefaultTemplates]) and is overlaid
 * by [com.tricreta.scopesms.domain.templates.TemplateSnapshot.from]. That also
 * means improving the shipped wording needs no data migration, and can never
 * overwrite something the agent wrote.
 */
@Entity(tableName = "message_templates")
data class MessageTemplateEntity(
    /** Stored as the [TemplateType] name — see the DAO for why not an ordinal. */
    @PrimaryKey
    val type: String,

    val body: String,
) {
    /** Null if [type] isn't a known flow — see [MessageTemplateDao.observeAll]. */
    fun toDomainOrNull(): MessageTemplate? {
        val parsed = TemplateType.entries.firstOrNull { it.name == type } ?: return null
        return MessageTemplate(type = parsed, body = body, isDefault = false)
    }

    companion object {
        fun of(type: TemplateType, body: String): MessageTemplateEntity =
            MessageTemplateEntity(type = type.name, body = body)
    }
}
