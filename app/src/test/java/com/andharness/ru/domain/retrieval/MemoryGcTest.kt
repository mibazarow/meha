package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure GC decision. Only importance-0 memories untouched for more than
 * [MemoryGc.STALE_AFTER_MS] (180 days) may be collected; importance>=1 memories and rows with an
 * unknown timestamp are always kept.
 */
class MemoryGcTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun oldUnimportantMemoryIsEligible() {
        assertTrue(MemoryGc.shouldGc(0, now - MemoryGc.STALE_AFTER_MS - 1, 0, now))
    }

    @Test
    fun freshUnimportantMemoryIsKept() {
        assertFalse(MemoryGc.shouldGc(0, now - 179 * day, 0, now))
    }

    @Test
    fun exactlyAtTheThresholdIsKept() {
        assertFalse("only strictly older than the window is collected",
            MemoryGc.shouldGc(0, now - MemoryGc.STALE_AFTER_MS, 0, now))
    }

    @Test
    fun importantAndCriticalMemoriesAreNeverCollected() {
        val veryOld = now - 3650 * day
        assertFalse("importance 1 must survive", MemoryGc.shouldGc(1, veryOld, veryOld, now))
        assertFalse("importance 2 must survive", MemoryGc.shouldGc(2, veryOld, veryOld, now))
    }

    @Test
    fun unknownTimestampsAreKept() {
        assertFalse("staleness cannot be proven without a timestamp", MemoryGc.shouldGc(0, 0, 0, now))
    }

    @Test
    fun createdAtIsTheReferenceBeforeTheFirstRecall() {
        assertTrue(MemoryGc.shouldGc(0, 0, now - MemoryGc.STALE_AFTER_MS - 1, now))
        assertFalse(MemoryGc.shouldGc(0, 0, now - day, now))
    }

    @Test
    fun lastUsedAtOverridesCreatedAtOnceRecalled() {
        assertFalse(
            "a recently recalled old memory must survive",
            MemoryGc.shouldGc(0, now - day, now - 3650 * day, now)
        )
        assertTrue(
            "an old last recall makes even a new memory eligible",
            MemoryGc.shouldGc(0, now - MemoryGc.STALE_AFTER_MS - 1, now - day, now)
        )
    }
}
