package com.andharness.ru.data.skill

import com.andharness.ru.domain.skill.Skill
import com.andharness.ru.domain.skill.SkillGcPolicy
import com.andharness.ru.domain.skill.SkillStep
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the bounded idle-skill GC runner: it deletes only expired skills through the store,
 * leaves fresh ones alone, and never throws (so it is safe on the startup path and the library screen).
 */
class SkillGcRunnerTest {

    private val dirs = mutableListOf<File>()
    private val now = 1_700_000_000_000L

    private fun newStore(): SkillStore {
        val dir = Files.createTempDirectory("skill-gc-test").toFile().also { dirs += it }
        return SkillStore(dir)
    }

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun skill(id: String, createdAt: Long, lastUsedAt: Long? = null): Skill = Skill(
        id = id,
        name = "Навык $id",
        appPackage = "com.app",
        steps = listOf(SkillStep.Delay(100)),
        createdAt = createdAt,
        updatedAt = createdAt,
        lastUsedAt = lastUsedAt,
    )

    @Test
    fun deletesOnlyExpiredSkillsAndReportsTheCount() {
        val store = newStore()
        store.save(skill("stale", createdAt = now - SkillGcPolicy.MAX_IDLE_MS - 1))
        store.save(skill("fresh", createdAt = now - 1000))
        store.save(skill("used", createdAt = now - SkillGcPolicy.MAX_IDLE_MS * 10, lastUsedAt = now - 1000))

        val deleted = SkillGcRunner(store).run(now)

        assertEquals(1, deleted)
        assertNull(store.get("stale"))
        assertNotNull(store.get("fresh"))
        assertNotNull(store.get("used"))
    }

    @Test
    fun emptyStoreDeletesNothing() {
        val store = newStore()
        assertEquals(0, SkillGcRunner(store).run(now))
        assertEquals(0, store.count())
    }

    @Test
    fun deletionsArePersistedToDisk() {
        val dir = Files.createTempDirectory("skill-gc-persist").toFile().also { dirs += it }
        val store = SkillStore(dir)
        store.save(skill("stale", createdAt = now - SkillGcPolicy.MAX_IDLE_MS - 1))
        SkillGcRunner(store).run(now)

        assertEquals(0, SkillStore(dir).count())
    }
}
