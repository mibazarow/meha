package com.andharness.ru.data.style

import com.andharness.ru.data.agent.StyleBlockBuilder
import com.andharness.ru.data.agent.StyleObserver
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end (minus the LLM card) check that repeated human messages actually become a prompt
 * block: analyzer → profanity gate → reducer → store → renderer.
 */
class StyleLearningIntegrationTest {

    private val dirs = mutableListOf<File>()

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newStore(): StyleProfileStore =
        StyleProfileStore(Files.createTempDirectory("style-integration").toFile().also { dirs += it })

    private fun observeOnDays(
        store: StyleProfileStore,
        text: String,
        days: Int,
        perDay: Int,
    ) {
        val zone = ZoneOffset.UTC
        for (dayOffset in 0 until days) {
            val dayStart = LocalDate.parse("2026-10-01")
                .plusDays(dayOffset.toLong())
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            repeat(perDay) { index ->
                StyleObserver.observe(store, text, dayStart + index * 60_000L, zone)
            }
        }
    }

    @Test
    fun repeatedShortInformalMessagesProduceABlock() {
        val store = newStore()

        observeOnDays(store, "ты тут?", days = 3, perDay = 5)

        val block = StyleBlockBuilder.render(store.current())
        assertTrue(block.contains("на «ты»"))
        assertTrue(block.contains("коротко"))
    }

    @Test
    fun profanityIsMirroredOnlyAfterEnoughSeparateDays() {
        val store = newStore()

        observeOnDays(store, "бля, ну нахуй это делать", days = 4, perDay = 5)

        val block = StyleBlockBuilder.render(store.current())
        assertTrue(block.contains("крепко выражается"))
        assertTrue(block.contains("Мат: максимум 1 слово"))
    }

    @Test
    fun aSingleIntenseDayDoesNotActivateProfanity() {
        val store = newStore()

        observeOnDays(store, "бля, ну нахуй это делать", days = 1, perDay = 30)

        val block = StyleBlockBuilder.render(store.current())
        assertFalse(block.contains("крепко выражается"))
    }
}
