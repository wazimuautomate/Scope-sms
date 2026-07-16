package com.tricreta.scopesms.data.templates

import com.tricreta.scopesms.domain.templates.MessageTemplate
import com.tricreta.scopesms.domain.templates.MessageTemplateRepository
import com.tricreta.scopesms.domain.templates.TemplateType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed reply bodies.
 *
 * Rows whose `type` no longer parses are dropped rather than surfaced. That can
 * only happen if a future phase renames a [TemplateType] and skips the
 * migration; when it does, the agent's custom wording for that flow reverts to
 * the shipped default — visibly wrong on the Templates screen, which is a far
 * better outcome than the alternative of crashing the SMS receiver on every
 * incoming payment.
 */
class RoomMessageTemplateRepository(
    private val dao: MessageTemplateDao,
) : MessageTemplateRepository {

    override fun observeAll(): Flow<List<MessageTemplate>> =
        dao.observeAll().map { rows -> rows.mapNotNull(MessageTemplateEntity::toDomainOrNull) }

    override suspend fun getAll(): List<MessageTemplate> =
        dao.getAll().mapNotNull(MessageTemplateEntity::toDomainOrNull)

    override suspend fun save(type: TemplateType, body: String) =
        dao.upsert(MessageTemplateEntity.of(type, body))

    override suspend fun resetToDefault(type: TemplateType) = dao.deleteByType(type.name)
}
