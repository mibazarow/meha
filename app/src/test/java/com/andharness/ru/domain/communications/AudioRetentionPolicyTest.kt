package com.andharness.ru.domain.communications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection rules for the audio retention GC: aged-out files are deleted unless kept, and the cache
 * cap evicts the oldest survivors. Pure JVM — no files are touched here.
 */
class AudioRetentionPolicyTest {

    private val day = AudioRetentionPolicy.MILLIS_PER_DAY
    private val now = 1_000L * day

    private fun candidate(
        id: Long,
        ageDays: Long,
        sizeBytes: Long = 10,
        keepFlag: Boolean = false,
    ) = AudioRetentionCandidate(
        id = id,
        sizeBytes = sizeBytes,
        createdAt = now - ageDays * day,
        keepFlag = keepFlag,
    )

    @Test
    fun `files older than the window are deleted`() {
        val plan = AudioRetentionPolicy.plan(
            candidates = listOf(
                candidate(id = 1, ageDays = 5),
                candidate(id = 2, ageDays = 2),
            ),
            nowMillis = now,
            keepAudioDays = 3,
            cacheLimitBytes = Long.MAX_VALUE,
        )
        assertEquals(listOf(1L), plan.expiredIds)
        assertTrue(plan.overflowIds.isEmpty())
    }

    @Test
    fun `age exactly at the boundary is kept`() {
        val plan = AudioRetentionPolicy.plan(
            candidates = listOf(candidate(id = 1, ageDays = 3)),
            nowMillis = now,
            keepAudioDays = 3,
            cacheLimitBytes = Long.MAX_VALUE,
        )
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test
    fun `keep flag protects an expired file`() {
        val plan = AudioRetentionPolicy.plan(
            candidates = listOf(
                candidate(id = 1, ageDays = 30, keepFlag = true),
                candidate(id = 2, ageDays = 30, keepFlag = false),
            ),
            nowMillis = now,
            keepAudioDays = 1,
            cacheLimitBytes = Long.MAX_VALUE,
        )
        assertEquals(listOf(2L), plan.deleteIds)
    }

    @Test
    fun `cache cap evicts the oldest survivors first`() {
        val plan = AudioRetentionPolicy.plan(
            candidates = listOf(
                candidate(id = 1, ageDays = 0, sizeBytes = 100),
                candidate(id = 2, ageDays = 0, sizeBytes = 100),
                candidate(id = 3, ageDays = 0, sizeBytes = 100),
            ),
            nowMillis = now,
            keepAudioDays = 30,
            cacheLimitBytes = 250,
        )
        // Total 300 > cap 250: delete the oldest (id 1), leaving 200.
        assertEquals(listOf(1L), plan.overflowIds)
        assertTrue(plan.expiredIds.isEmpty())
    }

    @Test
    fun `expired files are not counted against the cap and never duplicated`() {
        val plan = AudioRetentionPolicy.plan(
            candidates = listOf(
                candidate(id = 1, ageDays = 10, sizeBytes = 100),
                candidate(id = 2, ageDays = 0, sizeBytes = 100),
            ),
            nowMillis = now,
            keepAudioDays = 3,
            cacheLimitBytes = 150,
        )
        // id 1 expired (not re-listed as overflow); the single survivor fits the cap.
        assertEquals(listOf(1L), plan.expiredIds)
        assertTrue(plan.overflowIds.isEmpty())
        assertEquals(listOf(1L), plan.deleteIds)
    }

    @Test
    fun `non positive cap deletes every non kept survivor`() {
        val plan = AudioRetentionPolicy.plan(
            candidates = listOf(
                candidate(id = 1, ageDays = 0, keepFlag = true),
                candidate(id = 2, ageDays = 0),
                candidate(id = 3, ageDays = 0),
            ),
            nowMillis = now,
            keepAudioDays = 30,
            cacheLimitBytes = 0,
        )
        assertEquals(listOf(2L, 3L), plan.overflowIds)
        assertEquals(listOf(2L, 3L), plan.deleteIds)
    }

    @Test
    fun `everything kept yields an empty plan`() {
        val plan = AudioRetentionPolicy.plan(
            candidates = listOf(
                candidate(id = 1, ageDays = 100, keepFlag = true),
                candidate(id = 2, ageDays = 100, keepFlag = true),
            ),
            nowMillis = now,
            keepAudioDays = 1,
            cacheLimitBytes = 0,
        )
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test
    fun `oldest first ordering is deterministic for equal ages`() {
        val plan = AudioRetentionPolicy.plan(
            candidates = listOf(
                candidate(id = 3, ageDays = 0, sizeBytes = 100),
                candidate(id = 1, ageDays = 0, sizeBytes = 100),
                candidate(id = 2, ageDays = 0, sizeBytes = 100),
            ),
            nowMillis = now,
            keepAudioDays = 30,
            cacheLimitBytes = 100,
        )
        // All share createdAt, so the stable tie-breaker is the id: 1, then 2.
        assertEquals(listOf(1L, 2L), plan.overflowIds)
    }
}
