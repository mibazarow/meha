package com.andharness.ru.domain.automation

import kotlinx.coroutines.flow.Flow

/** Persistence for [AutomationRule] rows (Room v13, table `automation_rules`). */
interface AutomationRuleRepository {
    fun observeAll(): Flow<List<AutomationRule>>
    suspend fun getAll(): List<AutomationRule>
    suspend fun getById(id: Long): AutomationRule?
    suspend fun create(rule: AutomationRule): Long
    suspend fun update(rule: AutomationRule)
    suspend fun delete(id: Long): Boolean
    suspend fun setEnabled(id: Long, enabled: Boolean): Boolean
    suspend fun markRun(id: Long, timestamp: Long)

    /**
     * Atomically claims the occurrence [slot] for `id`; `true` when this caller won the claim and
     * must run it, `false` when that occurrence already ran (a WorkManager re-delivery).
     */
    suspend fun claimRun(id: Long, slot: Long): Boolean
}
