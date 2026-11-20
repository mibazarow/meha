package com.andharness.ru.data.style

import com.andharness.ru.domain.style.StyleAnalyzer
import com.andharness.ru.domain.style.StyleMode
import com.andharness.ru.domain.style.StyleProfile
import com.andharness.ru.domain.style.StyleTrait
import com.andharness.ru.domain.style.StyleTraitKey
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleProfileStoreTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("style-profile-test").toFile().also { dirs += it }

    private fun store(dir: File = newDir()) = StyleProfileStore(dir)

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun newStoreIsEmpty() {
        val store = store()

        assertEquals(StyleProfile(), store.current())
        assertEquals(0, store.current().samples)
    }

    @Test
    fun observeUpdatesAndPersistsAcrossInstances() {
        val dir = newDir()
        val store = store(dir)

        val updated = store.observe(StyleAnalyzer.analyze("ок"), now = 1_000L, day = 20_000L)

        assertEquals(1, updated.samples)
        assertEquals(1, updated.sessions)
        assertEquals(1, updated.trait(StyleTraitKey.BREVITY)?.observations)
        assertEquals(updated, store(dir).current())
    }

    @Test
    fun corruptFileDegradesToEmptyProfile() {
        val dir = newDir()
        dir.mkdirs()
        File(dir, StyleProfileStore.FILE_NAME).writeText("{ definitely not json")

        assertEquals(StyleProfile(), store(dir).current())
    }

    @Test
    fun modeIsPersisted() {
        val dir = newDir()
        val store = store(dir)

        assertTrue(store.setMode(StyleMode.NEUTRAL))
        assertEquals(StyleMode.NEUTRAL, store(dir).current().mode)
        assertFalse(store.setMode(StyleMode.NEUTRAL))
    }

    @Test
    fun manualCardIsTrimmedAndOverlongIsRejected() {
        val store = store()

        assertTrue(store.setManualCard("  пиши   по-дружески  "))
        assertEquals("пиши по-дружески", store.current().manualCard)
        assertFalse(store.setManualCard("x".repeat(StyleProfileRules.MAX_CARD_CHARS + 1)))
        assertEquals("пиши по-дружески", store.current().manualCard)

        assertTrue(store.setManualCard("   "))
        assertNull(store.current().manualCard)
    }

    @Test
    fun modelCardIsCappedInsteadOfRejected() {
        val store = store()

        assertTrue(store.setCard("x".repeat(500), now = 1_000L))

        assertEquals(StyleProfileRules.MAX_CARD_CHARS, store.current().card?.length)
        assertEquals(1_000L, store.current().cardUpdatedAt)
    }

    @Test
    fun clearForgetsLearnedDataButKeepsMode() {
        val store = store()
        store.setMode(StyleMode.MANUAL)
        store.setProfanityEnabled(false)
        store.observe(StyleAnalyzer.analyze("ок"), now = 1_000L, day = 20_000L)
        store.setManualCard("по-дружески")

        assertTrue(store.clear())

        assertEquals(
            StyleProfile(mode = StyleMode.MANUAL, profanityEnabled = false),
            store.current(),
        )
    }

    @Test
    fun sanitizeDedupesClampsAndBounds() {
        val trait = StyleTrait(
            key = StyleTraitKey.ADDRESS,
            value = 2.0,
            observations = 5,
            hitMessages = 9,
            dayKeys = (1L..30L).toList(),
        )
        val profile = StyleProfile(
            samples = -3,
            sessions = -1,
            traits = listOf(trait, trait.copy(value = 0.4)),
            card = "x".repeat(StyleProfileRules.MAX_CARD_CHARS + 1),
        )

        val sanitized = StyleProfileRules.sanitize(profile)

        assertEquals(0, sanitized.samples)
        assertEquals(0, sanitized.sessions)
        assertEquals(1, sanitized.traits.size)
        assertEquals(0.4, sanitized.traits.first().value, 0.0001)
        assertEquals(5, sanitized.traits.first().hitMessages)
        assertEquals(StyleProfileRules.MAX_DAY_KEYS, sanitized.traits.first().dayKeys.size)
        assertNull(sanitized.card)
    }
}
