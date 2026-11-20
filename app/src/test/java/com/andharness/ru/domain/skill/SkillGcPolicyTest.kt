package com.andharness.ru.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure idle-skill GC policy (M2): the 20-day threshold, the `lastUsedAt ?:
 * createdAt` decay reference, the strict `>` boundary, and the "unknown age is kept" rule.
 */
class SkillGcPolicyTest {

    private val now = 1_700_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun skill(createdAt: Long, lastUsedAt: Long? = null, id: String = "id-1"): Skill = Skill(
        id = id,
        name = "Навык",
        steps = listOf(SkillStep.Delay(100)),
        createdAt = createdAt,
        updatedAt = createdAt,
        lastUsedAt = lastUsedAt,
    )

    @Test
    fun neverUsedSkillExpiresAfterTwentyDays() {
        val s = skill(createdAt = now - SkillGcPolicy.MAX_IDLE_MS - 1)
        assertTrue(SkillGcPolicy.isExpired(s, now))
    }

    @Test
    fun neverUsedSkillOlderThanTwentyDaysIsNotYetExpired() {
        val s = skill(createdAt = now - 19 * day)
        assertFalse(SkillGcPolicy.isExpired(s, now))
    }

    @Test
    fun recentlyUsedSkillIsNotExpiredEvenWhenCreatedLongAgo() {
        val s = skill(createdAt = now - 365 * day, lastUsedAt = now - day)
        assertFalse(SkillGcPolicy.isExpired(s, now))
    }

    @Test
    fun usedSkillExpiresAfterTwentyDaysOfIdleTime() {
        val s = skill(createdAt = now - 365 * day, lastUsedAt = now - SkillGcPolicy.MAX_IDLE_MS - 1)
        assertTrue(SkillGcPolicy.isExpired(s, now))
    }

    @Test
    fun exactlyTwentyDaysIsTheBoundaryAndIsNotExpired() {
        val s = skill(createdAt = now - SkillGcPolicy.MAX_IDLE_MS)
        assertFalse("the threshold is strict (more than 20 days)", SkillGcPolicy.isExpired(s, now))
        assertTrue(SkillGcPolicy.isExpired(s, now + 1))
    }

    @Test
    fun nullLastUsedFallsBackToCreatedAt() {
        val s = skill(createdAt = now - SkillGcPolicy.MAX_IDLE_MS - 1, lastUsedAt = null)
        assertTrue(SkillGcPolicy.isExpired(s, now))
        // A recent run overrides the old creation date.
        assertFalse(SkillGcPolicy.isExpired(s.copy(lastUsedAt = now), now))
    }

    @Test
    fun unknownTimestampsAreKeptBecauseIdlenessCannotBeProven() {
        assertFalse(SkillGcPolicy.isExpired(skill(createdAt = 0L), now))
        assertFalse(SkillGcPolicy.isExpired(skill(createdAt = 0L, lastUsedAt = 0L), now))
    }

    @Test
    fun expiredFiltersOnlyTheStaleSkills() {
        val stale = skill(createdAt = now - SkillGcPolicy.MAX_IDLE_MS - 1, id = "stale")
        val fresh = skill(createdAt = now - day, id = "fresh")
        val used = skill(createdAt = 0L, lastUsedAt = now - day, id = "used")
        assertEquals(listOf("stale"), SkillGcPolicy.expired(listOf(stale, fresh, used), now).map { it.id })
        assertTrue(SkillGcPolicy.expired(emptyList(), now).isEmpty())
    }
}
