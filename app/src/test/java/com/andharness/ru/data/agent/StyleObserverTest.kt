package com.andharness.ru.data.agent

import com.andharness.ru.data.style.StyleProfileStore
import com.andharness.ru.domain.style.StyleTraitKey
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StyleObserverTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("style-observer-test").toFile().also { dirs += it }

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun observesTheMessageAndCountsMildProfanity() {
        val store = StyleProfileStore(newDir())

        StyleObserver.observe(store, "бля, ну нахуй это делать", now = 1_000L, zone = ZoneId.of("UTC"))

        assertEquals(1, store.current().samples)
        assertEquals(1, store.current().trait(StyleTraitKey.PROFANITY)?.hitMessages)
    }

    @Test
    fun hostileMessageDoesNotCountAsProfanity() {
        val store = StyleProfileStore(newDir())

        StyleObserver.observe(store, "ты мудак, бля", now = 1_000L, zone = ZoneId.of("UTC"))

        assertEquals(0, store.current().trait(StyleTraitKey.PROFANITY)?.hitMessages ?: 0)
    }

    @Test
    fun blankMessageAndNullStoreAreNoOps() {
        val store = StyleProfileStore(newDir())

        StyleObserver.observe(store, "   ", now = 1_000L, zone = ZoneId.of("UTC"))
        StyleObserver.observe(null, "привет", now = 1_000L, zone = ZoneId.of("UTC"))

        assertEquals(0, store.current().samples)
        assertNull(store.current().trait(StyleTraitKey.BREVITY))
    }

    @Test
    fun dayIsComputedInTheProvidedZone() {
        val store = StyleProfileStore(newDir())
        val now = Instant.parse("2026-10-20T23:30:00Z").toEpochMilli()

        StyleObserver.observe(store, "ок", now = now, zone = ZoneId.of("Europe/Moscow"))

        assertEquals(
            LocalDate.parse("2026-10-21").toEpochDay(),
            store.current().lastDay,
        )
    }
}
