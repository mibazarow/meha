package com.andharness.ru.data.audit

import com.andharness.ru.data.db.dao.ToolAuditDao
import com.andharness.ru.data.db.entity.ToolAuditEntity
import com.andharness.ru.domain.audit.ToolAuditRetention
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository-level checks of the tool-call audit log with an in-memory fake DAO, in the style of
 * [com.andharness.ru.data.automation.AutomationEventRepositoryImplTest]: recording links the hash
 * chain, `prune` applies both the age and the count cap, and chain verification still anchors on the
 * oldest retained row after trimming.
 */
class ToolAuditRepositoryImplTest {

    private class FakeToolAuditDao : ToolAuditDao {
        val rows = mutableListOf<ToolAuditEntity>()
        private var nextId = 1L

        override suspend fun insert(entity: ToolAuditEntity): Long {
            val id = nextId++
            rows += entity.copy(id = id)
            return id
        }

        override suspend fun getAll(): List<ToolAuditEntity> = rows.sortedBy { it.id }

        override suspend fun getLatest(limit: Int): List<ToolAuditEntity> =
            rows.sortedByDescending { it.id }.take(limit)

        override suspend fun lastHash(): String? = rows.maxByOrNull { it.id }?.hash

        override suspend fun count(): Int = rows.size

        override suspend fun deleteOlderThan(cutoff: Long): Int {
            val doomed = rows.count { it.at < cutoff }
            rows.removeAll { it.at < cutoff }
            return doomed
        }

        override suspend fun pruneToLast(keep: Int): Int {
            if (rows.size <= keep) return 0
            val keepIds = rows.sortedByDescending { it.id }.take(keep).map { it.id }.toSet()
            val removed = rows.count { it.id !in keepIds }
            rows.removeAll { it.id !in keepIds }
            return removed
        }
    }

    @Test
    fun retentionCapIsTwoHundredEvents() {
        assertEquals(200, ToolAuditRetention.MAX_ENTRIES)
        assertEquals(30L * 24L * 60L * 60L * 1_000L, ToolAuditRetention.MAX_AGE_MS)
    }

    @Test
    fun pruneDropsRowsOlderThanTheWindowAndKeepsTheNewestCap() = runTest {
        val dao = FakeToolAuditDao()
        val repo = ToolAuditRepositoryImpl(dao)
        val now = 1_700_000_000_000L

        repo.record(
            at = now - ToolAuditRetention.MAX_AGE_MS - 1,
            chatId = null,
            tool = "files_list",
            argsJson = "{}",
            success = true,
            resultText = "stale",
            durationMs = 1L,
            model = null
        )
        repo.record(
            at = now - 1_000L,
            chatId = null,
            tool = "files_list",
            argsJson = "{}",
            success = true,
            resultText = "fresh",
            durationMs = 1L,
            model = null
        )

        repo.prune(now)
        assertEquals("the stale row is gone, the fresh one stays", 1, dao.rows.size)
        assertEquals(now - 1_000L, dao.rows.single().at)

        // The count cap is applied on top of the age window.
        repeat(ToolAuditRetention.MAX_ENTRIES + 3) { index ->
            repo.record(
                at = now + index,
                chatId = null,
                tool = "files_read",
                argsJson = """{"path":"a.txt"}""",
                success = true,
                resultText = "result-$index",
                durationMs = 1L,
                model = null
            )
        }
        repo.prune(now)
        assertEquals(ToolAuditRetention.MAX_ENTRIES, dao.rows.size)
    }

    @Test
    fun verifyStillPassesAfterPruningBecauseItAnchorsOnTheOldestRetainedRow() = runTest {
        val dao = FakeToolAuditDao()
        val repo = ToolAuditRepositoryImpl(dao)
        val now = 1_700_000_000_000L

        repeat(ToolAuditRetention.MAX_ENTRIES + 5) { index ->
            repo.record(
                at = now + index,
                chatId = null,
                tool = "files_read",
                argsJson = """{"path":"a.txt"}""",
                success = true,
                resultText = "result-$index",
                durationMs = 1L,
                model = null
            )
        }

        repo.prune(now)

        assertEquals(ToolAuditRetention.MAX_ENTRIES, repo.count())
        val result = repo.verify()
        assertTrue("trimmed chain must not report a false break: ${result.reason}", result.ok)
        assertEquals(ToolAuditRetention.MAX_ENTRIES, result.checked)
        assertTrue(
            "the first retained row keeps a real predecessor hash",
            repo.all().first().prevHash.isNotEmpty()
        )
    }
}
