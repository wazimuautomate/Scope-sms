package com.scopesms.autoreply.data.templates

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for the two reply bodies. At most two rows, ever.
 *
 * `type` is stored as the enum's *name*, not its ordinal, and not via a Room
 * `@TypeConverter` on the enum. Room's default enum handling stores the name
 * already, but doing it explicitly through [MessageTemplateEntity] makes the
 * stored value obvious from the schema JSON and immune to someone reordering
 * [com.scopesms.autoreply.domain.templates.TemplateType] — which, with
 * ordinals, would silently swap the agent's matched and unmatched wording and
 * start confirming purchases to customers who hadn't made one.
 */
@Dao
interface MessageTemplateDao {

    /** Both stored rows, re-emitting on change. Collected into the template cache. */
    @Query("SELECT * FROM message_templates")
    fun observeAll(): Flow<List<MessageTemplateEntity>>

    @Query("SELECT * FROM message_templates")
    suspend fun getAll(): List<MessageTemplateEntity>

    /** Saves the agent's wording, replacing any previous body for that flow. */
    @Upsert
    suspend fun upsert(template: MessageTemplateEntity)

    /** Deletes the row so the flow falls back to the shipped default. */
    @Query("DELETE FROM message_templates WHERE type = :type")
    suspend fun deleteByType(type: String)
}
