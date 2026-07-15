package com.scopesms.autoreply.domain.templates

import kotlinx.coroutines.flow.Flow

/**
 * Durable storage for the two reply bodies. Implemented by
 * `data/templates/RoomMessageTemplateRepository`.
 *
 * Only rows the agent has actually customised are stored; an untouched flow has
 * no row and [TemplateSnapshot] fills it from [DefaultTemplates]. That's why
 * there is no `insertDefaults()` here — there is nothing to insert.
 */
interface MessageTemplateRepository {

    /** Emits stored templates, re-emitting on every change. Collected into [TemplateCache]. */
    fun observeAll(): Flow<List<MessageTemplate>>

    suspend fun getAll(): List<MessageTemplate>

    /** Saves the agent's wording for [type], replacing any previous body. */
    suspend fun save(type: TemplateType, body: String)

    /** Drops the agent's wording for [type], returning it to [DefaultTemplates]. */
    suspend fun resetToDefault(type: TemplateType)
}
