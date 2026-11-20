package com.andharness.ru.data.automation

import com.andharness.ru.data.db.dao.AutomationEventDao
import com.andharness.ru.data.db.entity.AutomationEventEntity
import com.andharness.ru.domain.automation.AutomationEvent
import com.andharness.ru.domain.automation.AutomationEventRecord
import com.andharness.ru.domain.automation.AutomationEventRepository
import com.andharness.ru.domain.automation.AutomationEventRetention
import com.andharness.ru.domain.automation.AutomationRule
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [AutomationEventRepository]. Bounded by
 * [AutomationEventRetention]: at most 500 newest rows, nothing older than 14 days.
 */
@Singleton
class AutomationEventRepositoryImpl @Inject constructor(
    private val dao: AutomationEventDao
) : AutomationEventRepository {

    private fun toDomain(e: AutomationEventEntity) = AutomationEventRecord(
        id = e.id,
        eventType = e.eventType,
        value = e.value,
        payload = e.payload,
        ruleId = e.ruleId,
        ruleName = e.ruleName,
        createdAt = e.createdAt
    )

    override suspend fun record(event: AutomationEvent, rule: AutomationRule): Long =
        dao.insert(
            AutomationEventEntity(
                eventType = event.triggerType,
                value = event.value,
                payload = event.payload,
                ruleId = rule.id,
                ruleName = rule.name,
                createdAt = event.at
            )
        )

    override suspend fun recent(limit: Int): List<AutomationEventRecord> =
        dao.recent(AutomationEventRetention.bound(limit)).map(::toDomain)

    override suspend fun recentByType(type: String, limit: Int): List<AutomationEventRecord> =
        dao.recentByType(type, AutomationEventRetention.bound(limit)).map(::toDomain)

    override suspend fun prune(now: Long) {
        dao.deleteOlderThan(AutomationEventRetention.cutoff(now))
        dao.pruneToLast(AutomationEventRetention.MAX_ENTRIES)
    }
}
