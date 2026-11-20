package com.andharness.ru.domain.notify

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BUGS.md M7: noise must get a REAL zero, otherwise `MemoryGcWorker` (which only collects
 * `importance == 0` after 180 untouched days) can never remove anything that triage wrote.
 */
class MemoryPolicyImportanceTest {

    private fun result(importance: TriageImportance) = TriageResult(
        summary = "Тест",
        importance = importance,
        category = TriageCategory.PERSONAL,
        reasons = emptyList()
    )

    @Test
    fun highImportanceIsCritical() {
        assertEquals(2, MemoryPolicy.memoryImportance(result(TriageImportance.HIGH)))
    }

    @Test
    fun normalImportanceIsCollectableNoise() {
        assertEquals(
            "a normal notification is worth a while, not forever — GC must be able to collect it",
            0,
            MemoryPolicy.memoryImportance(result(TriageImportance.NORMAL))
        )
    }

    @Test
    fun lowImportanceIsAlsoZero() {
        assertEquals(0, MemoryPolicy.memoryImportance(result(TriageImportance.LOW)))
    }

    @Test
    fun normalIsMemorizedButLowIsNot() {
        assertEquals(
            true,
            MemoryPolicy.shouldMemorize(result(TriageImportance.NORMAL), MemorizeMode.ALL)
        )
        assertEquals(
            false,
            MemoryPolicy.shouldMemorize(result(TriageImportance.LOW), MemorizeMode.ALL)
        )
    }

    @Test
    fun secretsAreNeverMemorized() {
        val secret = result(TriageImportance.HIGH).copy(secret = true)
        assertEquals(false, MemoryPolicy.shouldMemorize(secret, MemorizeMode.ALL))
    }
}
