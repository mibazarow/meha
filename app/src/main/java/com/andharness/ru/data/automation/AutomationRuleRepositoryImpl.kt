package com.andharness.ru.data.automation

import com.andharness.ru.data.db.dao.AutomationRuleDao
import com.andharness.ru.data.db.entity.AutomationRuleEntity
import com.andharness.ru.domain.automation.AutomationRule
import com.andharness.ru.domain.automation.AutomationRuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationRuleRepositoryImpl @Inject constructor(
    private val dao: AutomationRuleDao
) : AutomationRuleRepository {

    private fun toDomain(e: AutomationRuleEntity) = AutomationRule(
        id = e.id,
        name = e.name,
        enabled = e.enabled,
        triggerType = e.triggerType,
        triggerConfig = e.triggerConfig,
        actionType = e.actionType,
        actionConfig = e.actionConfig,
        createdAt = e.createdAt,
        lastRunAt = e.lastRunAt
    )

    private fun toEntity(r: AutomationRule) = AutomationRuleEntity(
        id = r.id,
        name = r.name,
        enabled = r.enabled,
        triggerType = r.triggerType,
        triggerConfig = r.triggerConfig,
        actionType = r.actionType,
        actionConfig = r.actionConfig,
        createdAt = r.createdAt,
        lastRunAt = r.lastRunAt
    )

    override fun observeAll(): Flow<List<AutomationRule>> =
        dao.observeAll().map { list -> list.map(::toDomain) }

    override suspend fun getAll(): List<AutomationRule> = dao.getAll().map(::toDomain)

    override suspend fun getById(id: Long): AutomationRule? = dao.getById(id)?.let(::toDomain)

    override suspend fun create(rule: AutomationRule): Long = dao.insert(toEntity(rule))

    override suspend fun update(rule: AutomationRule) = dao.update(toEntity(rule))

    override suspend fun delete(id: Long): Boolean = dao.deleteById(id) > 0

    override suspend fun setEnabled(id: Long, enabled: Boolean): Boolean {
        val existing = dao.getById(id) ?: return false
        dao.update(existing.copy(enabled = enabled))
        return true
    }

    override suspend fun markRun(id: Long, timestamp: Long) = dao.setLastRun(id, timestamp)

    override suspend fun claimRun(id: Long, slot: Long): Boolean = dao.claimRun(id, slot) > 0
}
