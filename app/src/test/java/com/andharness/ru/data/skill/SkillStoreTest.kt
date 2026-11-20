package com.andharness.ru.data.skill

import com.andharness.ru.domain.skill.Skill
import com.andharness.ru.domain.skill.SkillCodec
import com.andharness.ru.domain.skill.SkillStep
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the file-backed [SkillStore]. It is Android-free by construction (it only takes a
 * directory), so real file CRUD, persistence across instances, the count cap and corrupt-file
 * degradation are all exercised here on a temp directory — no Room/Android runtime involved.
 */
class SkillStoreTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("skill-store-test").toFile().also { dirs += it }

    private fun store(dir: File = newDir()) = SkillStore(dir)

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun skill(id: String, name: String = "Навык $id"): Skill = Skill(
        id = id,
        name = name,
        appPackage = "com.app",
        steps = listOf(SkillStep.Delay(100)),
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun saveListGetDeleteRoundTrip() {
        val store = store()
        assertTrue(store.list().isEmpty())

        val saved = store.save(skill("a", "Заказать такси"))
        assertNotNull(saved)
        assertEquals(1, store.count())
        assertEquals("Заказать такси", store.get("a")?.name)
        assertEquals("a", store.get("Заказать такси")?.id)
        // Name lookup is case-insensitive as a fallback.
        assertEquals("a", store.get("заказать такси")?.id)

        assertTrue(store.delete("Заказать такси"))
        assertTrue(store.list().isEmpty())
        assertFalse(store.delete("нет такого"))
    }

    @Test
    fun savingSameIdReplacesInsteadOfDuplicating() {
        val store = store()
        store.save(skill("a", "Первый"))
        store.save(skill("a", "Второй"))
        assertEquals(1, store.count())
        assertEquals("Второй", store.get("a")?.name)
    }

    @Test
    fun persistsAcrossStoreInstances() {
        val dir = newDir()
        store(dir).save(skill("a", "Живучий"))
        val reopened = store(dir)
        assertEquals(1, reopened.count())
        assertEquals("Живучий", reopened.get("a")?.name)
    }

    @Test
    fun trimsToMaxKeepingNewest() {
        val store = store()
        (0 until SkillCodec.MAX_SKILLS + 5).forEach { store.save(skill("id$it")) }
        val all = store.list()
        assertEquals(SkillCodec.MAX_SKILLS, all.size)
        assertEquals("id5", all.first().id)
        assertEquals("id${SkillCodec.MAX_SKILLS + 4}", all.last().id)
    }

    @Test
    fun corruptFileDegradesToEmptyList() {
        val dir = newDir()
        File(dir, SkillStore.FILE_NAME).writeText("this is not json")
        val store = store(dir)
        assertTrue(store.list().isEmpty())
        // The store must still be usable after a corrupt file.
        assertNotNull(store.save(skill("a")))
        assertEquals(1, store.count())
    }

    @Test
    fun blankIdOrNameIsRejected() {
        val store = store()
        assertNull(store.save(skill("", "Без id")))
        assertNull(store.save(skill("a", "  ")))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun stateFlowReflectsChanges() {
        val store = store()
        assertEquals(0, store.skills.value.size)
        store.save(skill("a"))
        assertEquals(1, store.skills.value.size)
        store.delete("a")
        assertEquals(0, store.skills.value.size)
    }

    @Test
    fun touchStampsLastUsedAtAndPersists() {
        val dir = newDir()
        val store = SkillStore(dir)
        store.save(skill("a"))
        assertNull("a never-run skill has no lastUsedAt", store.get("a")?.lastUsedAt)

        assertTrue(store.touch("a", now = 123_456L))
        assertEquals(123_456L, store.get("a")?.lastUsedAt)
        // The content timestamp (updatedAt) is deliberately left untouched by a usage stamp.
        assertEquals(1L, store.get("a")?.updatedAt)

        // The stamp survives reopening the file-backed store.
        assertEquals(123_456L, SkillStore(dir).get("a")?.lastUsedAt)
    }

    @Test
    fun touchUnknownSkillIsANoOp() {
        val store = store()
        assertFalse(store.touch("нет такого", now = 5L))
        assertTrue(store.list().isEmpty())
    }
}
