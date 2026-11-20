package com.andharness.ru.data.skill

import com.andharness.ru.domain.skill.SkillGcPolicy

/**
 * Best-effort, bounded application of the idle-skill GC policy (M2, SPEC §24.2).
 *
 * It is deliberately Android-free (it only takes the file-backed [SkillStore]) so it is exercised on
 * the JVM (`SkillGcRunnerTest`); the two call sites — `AndharnessApplication` at startup and
 * `SkillLibraryViewModel` when the library loads — only add logging. It **never throws**: a failed GC
 * must never break startup or the skills screen, so any error is swallowed and reported as 0 deleted.
 *
 * The scan is inherently bounded by [com.andharness.ru.domain.skill.SkillCodec.MAX_SKILLS] (the store
 * keeps at most that many skills) and deletion goes through the same [SkillStore.delete] path the
 * `skill_delete` tool and the UI use. An empty store deletes nothing.
 */
class SkillGcRunner(private val store: SkillStore) {

    /** Deletes every skill expired at [now]; returns how many were removed. Never throws. */
    fun run(now: Long = System.currentTimeMillis()): Int = try {
        val expired = SkillGcPolicy.expired(store.list(), now)
        var deleted = 0
        for (skill in expired) if (store.delete(skill.id)) deleted++
        deleted
    } catch (_: Exception) {
        0
    }
}
