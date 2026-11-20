package com.andharness.ru.domain.skill

/**
 * Pure GC decision for idle app skills (M2, SPEC §24.2). Deliberately Android-free and side-effect
 * free so the whole policy is exercised on the JVM (`SkillGcPolicyTest`); the Android-bound runner
 * (`com.andharness.ru.data.skill.SkillGcRunner`) only applies it through `SkillStore.delete`.
 *
 * A skill is idle when its decay reference — the last successful real run [Skill.lastUsedAt], or
 * [Skill.createdAt] before the first run — is older than [MAX_IDLE_MS] (20 days). The comparison is
 * strict (`>`), so a skill is expired only once it has been untouched for **more than** 20 days; a
 * skill with an unknown timestamp (0/absent) is kept, because idleness cannot be proven.
 */
object SkillGcPolicy {

    /** A skill unused for longer than this is eligible for collection (20 days). */
    const val MAX_IDLE_MS = 20L * 24 * 60 * 60 * 1000

    /**
     * True when [skill] may be deleted at [now]: never used since [Skill.createdAt], or not
     * successfully run since [Skill.lastUsedAt], for more than [MAX_IDLE_MS].
     */
    fun isExpired(skill: Skill, now: Long): Boolean {
        val reference = skill.lastUsedAt ?: skill.createdAt
        if (reference <= 0L) return false
        return now - reference > MAX_IDLE_MS
    }

    /** The skills in [skills] that are expired at [now], preserving their order. */
    fun expired(skills: List<Skill>, now: Long): List<Skill> =
        skills.filter { isExpired(it, now) }
}
