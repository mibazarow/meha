package com.andharness.ru.domain.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure checks of the fired-event retention policy: the log is capped by both count
 * and age, and a caller's `limit` is always bounded so one read cannot dump the whole table.
 */
class AutomationEventRetentionTest {

    @Test
    fun retentionIsBoundedByCountAndAge() {
        assertEquals(500, AutomationEventRetention.MAX_ENTRIES)
        assertEquals(14L * 24 * 60 * 60 * 1000, AutomationEventRetention.MAX_AGE_MS)
    }

    @Test
    fun cutoffIsExactlyFourteenDaysBeforeNow() {
        val now = 1_700_000_000_000L
        assertEquals(now - AutomationEventRetention.MAX_AGE_MS, AutomationEventRetention.cutoff(now))
    }

    @Test
    fun absentLimitFallsBackToDefault() {
        assertEquals(AutomationEventRetention.DEFAULT_LIMIT, AutomationEventRetention.bound(null))
    }

    @Test
    fun limitIsClampedToTheAllowedWindow() {
        assertEquals(1, AutomationEventRetention.bound(0))
        assertEquals(1, AutomationEventRetention.bound(-5))
        assertEquals(AutomationEventRetention.MAX_LIMIT, AutomationEventRetention.bound(9_999))
        assertEquals(7, AutomationEventRetention.bound(7))
        assertTrue(AutomationEventRetention.MAX_LIMIT >= AutomationEventRetention.DEFAULT_LIMIT)
    }
}
