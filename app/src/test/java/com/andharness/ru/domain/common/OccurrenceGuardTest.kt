package com.andharness.ru.domain.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the work-item idempotency predicate shared by the self-chaining cron
 * workers (ScheduleWorker / AutomationTimeWorker). WorkManager is at-least-once, so the same
 * occurrence must be recognised and skipped on a re-delivery.
 */
class OccurrenceGuardTest {

    private val now = 1_700_000_000_000L

    @Test
    fun slotIsTheAbsoluteOccurrenceInstant() {
        assertEquals(now + 60_000L, OccurrenceGuard.slotMillis(now, 60_000L))
        assertEquals(now, OccurrenceGuard.slotMillis(now, 0L))
    }

    @Test
    fun neverRunIsNotExecuted() {
        assertFalse(OccurrenceGuard.alreadyExecuted(lastRunAt = null, slotMillis = now))
    }

    @Test
    fun earlierRunDoesNotClaimThisSlot() {
        assertFalse(OccurrenceGuard.alreadyExecuted(lastRunAt = now - 1, slotMillis = now))
        assertFalse(OccurrenceGuard.alreadyExecuted(lastRunAt = now - 60_000L, slotMillis = now))
    }

    @Test
    fun sameSlotAlreadyRanIsSkipped() {
        assertTrue(OccurrenceGuard.alreadyExecuted(lastRunAt = now, slotMillis = now))
    }

    @Test
    fun laterSlotAlreadyRanAlsoSkipsAnOutOfOrderRedelivery() {
        assertTrue(OccurrenceGuard.alreadyExecuted(lastRunAt = now + 60_000L, slotMillis = now))
    }
}
