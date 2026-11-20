package com.andharness.ru.domain.automation

import com.andharness.ru.domain.automation.RuleRateLimiter.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleRateLimiterTest {

    @Test
    fun secondFireInsideMinIntervalIsThrottled() {
        val limiter = RuleRateLimiter()
        assertEquals(Decision.Fire, limiter.evaluate(1L, 1_000L))
        assertEquals(Decision.SkipThrottled, limiter.evaluate(1L, 2_000L))
    }

    @Test
    fun firesSeparatedByMoreThanMinIntervalAreAllowed() {
        val limiter = RuleRateLimiter()
        assertEquals(Decision.Fire, limiter.evaluate(1L, 0L))
        assertEquals(Decision.Fire, limiter.evaluate(1L, 6_000L))
    }

    @Test
    fun sixthFireInWindowRequestsDisableAndResetClearsIt() {
        val limiter = RuleRateLimiter()
        repeat(RuleRateLimiter.NOISY_MAX_FIRES - 1) { index ->
            assertEquals(Decision.Fire, limiter.evaluate(7L, index * 6_000L))
        }
        assertEquals(
            Decision.DisableNoisy,
            limiter.evaluate(7L, (RuleRateLimiter.NOISY_MAX_FIRES - 1) * 6_000L)
        )

        limiter.reset(7L)
        assertEquals(Decision.Fire, limiter.evaluate(7L, 5 * 60_000L))
    }

    @Test
    fun noisyRuleStaysSuppressedUntilReset() {
        val limiter = RuleRateLimiter()
        repeat(RuleRateLimiter.NOISY_MAX_FIRES) { index ->
            limiter.evaluate(9L, index * 6_000L)
        }
        assertEquals(Decision.SkipThrottled, limiter.evaluate(9L, 6 * 60_000L))
        assertEquals(Decision.SkipThrottled, limiter.evaluate(9L, 7 * 60_000L))
    }

    @Test
    fun differentRuleIdsAreTrackedIndependently() {
        val limiter = RuleRateLimiter()
        assertEquals(Decision.Fire, limiter.evaluate(1L, 0L))
        assertEquals(Decision.Fire, limiter.evaluate(2L, 1_000L))
        assertEquals(Decision.SkipThrottled, limiter.evaluate(1L, 1_000L))
        assertEquals(Decision.SkipThrottled, limiter.evaluate(2L, 2_000L))
    }
}
