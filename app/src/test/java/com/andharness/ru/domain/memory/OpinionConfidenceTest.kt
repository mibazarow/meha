package com.andharness.ru.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decay semantics of [OpinionConfidence]: exponential half-life decay, clamping to 0..1 and
 * safety against a future (clock-skewed) createdAt.
 */
class OpinionConfidenceTest {

    private val day = 86_400_000L
    private val created = 1_700_000_000_000L

    @Test
    fun confidenceDecaysMonotonicallyWithAge() {
        val fresh = OpinionConfidence.effective(0.8f, created, created)
        val month = OpinionConfidence.effective(0.8f, created, created + 30 * day)
        val year = OpinionConfidence.effective(0.8f, created, created + 365 * day)
        assertTrue("older must never exceed fresher", fresh >= month)
        assertTrue("older must never exceed fresher", month >= year)
        assertTrue("decay must strictly lower the value over time", fresh > year)
    }

    @Test
    fun halfLifeHalvesTheConfidence() {
        val before = OpinionConfidence.effective(0.8f, created, created)
        val after = OpinionConfidence.effective(0.8f, created, created + 90 * day)
        assertEquals("90 days is one half-life", 0.4f, after, 1e-4f)
        assertEquals(before, 0.8f, 1e-4f)
    }

    @Test
    fun confidenceAndResultAreClampedToUnitRange() {
        assertEquals("above 1 is clamped", 1.0f, OpinionConfidence.effective(2.0f, created, created), 1e-4f)
        assertEquals("below 0 is clamped", 0.0f, OpinionConfidence.effective(-1.0f, created, created), 1e-4f)
        val tiny = OpinionConfidence.effective(0.5f, created, created + 100_000L * day)
        assertTrue("result never goes below 0", tiny >= 0.0f)
    }

    @Test
    fun futureCreatedAtIsTreatedAsAgeZero() {
        val value = OpinionConfidence.effective(0.8f, created + 10 * day, created)
        assertEquals("clock skew must not amplify confidence", 0.8f, value, 1e-4f)
    }

    @Test
    fun nonPositiveHalfLifeFallsBackToOneDay() {
        val value = OpinionConfidence.effective(0.8f, created, created + day, halfLifeDays = 0)
        assertEquals(0.4f, value, 1e-4f)
    }
}
