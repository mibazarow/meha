package com.andharness.ru.data.automation

import com.andharness.ru.data.db.dao.AutomationEventDao
import com.andharness.ru.data.db.entity.AutomationEventEntity
import com.andharness.ru.domain.automation.AutomationEvent
import com.andharness.ru.domain.automation.AutomationEventRetention
import com.andharness.ru.domain.automation.AutomationRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository-level checks of the fired-event log with an in-memory fake DAO, in the
 * style of [com.andharness.ru.data.assist.AssistRepositoryImplTest]: recording copies the trigger
 * occurrence + rule identity, reads are newest-first and bounded, and `prune` applies both the age
 * and the count cap.
 */
class AutomationEventRepositoryImplTest {

    private class FakeAutomationEventDao : AutomationEventDao {
        val rows = mutableListOf<AutomationEventEntity>()
        private var nextId = 1L

        override suspend fun insert(entity: AutomationEventEntity): Long {
            val id = nextId++
            rows += entity.copy(id = id)
            return id
        }

        override suspend fun recent(limit: Int): List<AutomationEventEntity> =
            rows.sortedWith(NEWEST_FIRST).take(limit)

        override suspend fun recentByType(type: String, limit: Int): List<AutomationEventEntity> =
            rows.filter { it.eventType == type }.sortedWith(NEWEST_FIRST).take(limit)

        override suspend fun deleteOlderThan(cutoff: Long): Int {
            val doomed = rows.filter { it.createdAt < cutoff }
            rows.removeAll { it.createdAt < cutoff }
            return doomed.size
        }

        override suspend fun pruneToLast(keep: Int): Int {
            if (rows.size <= keep) return 0
            val keepIds = rows.sortedWith(NEWEST_FIRST).take(keep).map { it.id }.toSet()
            val removed = rows.count { it.id !in keepIds }
            rows.removeAll { it.id !in keepIds }
            return removed
        }
    }

    private fun rule(id: Long = 7, name: String = "Батарея разряжена") =
        AutomationRule(id = id, name = name, triggerType = "battery_low", actionType = "notify")

    @Test
    fun recordCopiesTriggerAndRuleIdentity() = runTest {
        val dao = FakeAutomationEventDao()
        val repo = AutomationEventRepositoryImpl(dao)

        val id = repo.record(
            AutomationEvent(
                triggerType = "battery_low",
                value = "5%",
                payload = "level=5",
                at = 1_700_000_000_000L
            ),
            rule()
        )

        assertEquals(1L, id)
        val row = dao.rows.single()
        assertEquals("battery_low", row.eventType)
        assertEquals("5%", row.value)
        assertEquals("level=5", row.payload)
        assertEquals(7L, row.ruleId)
        assertEquals("Батарея разряжена", row.ruleName)
        assertEquals(1_700_000_000_000L, row.createdAt)
    }

    @Test
    fun recentIsNewestFirstAndHonoursTheBoundedLimit() = runTest {
        val dao = FakeAutomationEventDao()
        val repo = AutomationEventRepositoryImpl(dao)
        repo.record(AutomationEvent(triggerType = "boot", at = 100L), rule(id = 1, name = "A"))
        repo.record(AutomationEvent(triggerType = "boot", at = 300L), rule(id = 2, name = "B"))
        repo.record(AutomationEvent(triggerType = "boot", at = 200L), rule(id = 3, name = "C"))

        val all = repo.recent()
        assertEquals(listOf("B", "C", "A"), all.map { it.ruleName })

        assertEquals(2, repo.recent(limit = 2).size)
        assertEquals("a zero limit is clamped to one", 1, repo.recent(limit = 0).size)
        assertEquals("an oversized limit is clamped, not rejected", 3, repo.recent(limit = 9_999).size)
    }

    @Test
    fun recentByTypeFiltersToExactlyThatTrigger() = runTest {
        val dao = FakeAutomationEventDao()
        val repo = AutomationEventRepositoryImpl(dao)
        repo.record(AutomationEvent(triggerType = "battery_low", at = 1L), rule(id = 1, name = "low"))
        repo.record(AutomationEvent(triggerType = "boot", at = 2L), rule(id = 2, name = "boot"))
        repo.record(AutomationEvent(triggerType = "battery_low", at = 3L), rule(id = 3, name = "low2"))

        val lows = repo.recentByType("battery_low")
        assertEquals(listOf("low2", "low"), lows.map { it.ruleName })
        assertTrue(repo.recentByType("screen_on").isEmpty())
    }

    @Test
    fun pruneDropsRowsOlderThanTheWindowAndKeepsTheNewestCap() = runTest {
        val dao = FakeAutomationEventDao()
        val repo = AutomationEventRepositoryImpl(dao)
        val now = 1_700_000_000_000L
        repo.record(
            AutomationEvent(triggerType = "boot", at = now - AutomationEventRetention.MAX_AGE_MS - 1),
            rule()
        )
        repo.record(AutomationEvent(triggerType = "boot", at = now - 1_000L), rule())

        repo.prune(now)
        assertEquals("the stale row is gone, the fresh one stays", 1, dao.rows.size)
        assertEquals(now - 1_000L, dao.rows.single().createdAt)

        // The count cap is applied on top of the age window.
        repeat(AutomationEventRetention.MAX_ENTRIES + 3) { index ->
            repo.record(AutomationEvent(triggerType = "boot", at = now + index), rule())
        }
        repo.prune(now)
        assertEquals(AutomationEventRetention.MAX_ENTRIES, dao.rows.size)
    }

    private companion object {
        val NEWEST_FIRST =
            compareByDescending<AutomationEventEntity> { it.createdAt }.thenByDescending { it.id }
    }
}
