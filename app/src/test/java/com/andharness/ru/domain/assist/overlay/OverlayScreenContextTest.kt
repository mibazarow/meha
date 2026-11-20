package com.andharness.ru.domain.assist.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [OverlayScreenContext] — the compact current-screen block the overlay prepends to
 * the seeded chat message.
 *
 * Rules under test: a full context renders package + activity + labels; an empty context is omitted
 * (never fabricated); and the seeded payload is the question followed by the block.
 */
class OverlayScreenContextTest {

    @Test
    fun fullContextRendersPackageActivityAndLabels() {
        val block = OverlayScreenContext.format(
            ScreenContext(
                packageName = "com.example.shop",
                activityName = "com.example.shop.CartActivity",
                labels = listOf("Корзина", "Итого: 1 990 ₽"),
            )
        )
        assertTrue(block!!.startsWith(OverlayScreenContext.HEADER))
        assertTrue(block.contains("Приложение: com.example.shop"))
        assertTrue(block.contains("Активность: com.example.shop.CartActivity"))
        assertTrue(block.contains("Видимые элементы:"))
        assertTrue(block.contains("• Корзина"))
        assertTrue(block.contains("• Итого: 1 990 ₽"))
    }

    @Test
    fun emptyContextIsOmitted() {
        assertNull(OverlayScreenContext.format(ScreenContext()))
        assertNull(OverlayScreenContext.format(ScreenContext(packageName = "  ", labels = listOf("  "))))
        assertTrue(ScreenContext().isEmpty)
        assertFalse(ScreenContext(packageName = "com.example").isEmpty)
    }

    @Test
    fun labelsOnlyContextHasNoPackageOrActivityLines() {
        val block = OverlayScreenContext.format(ScreenContext(labels = listOf("Готово")))!!
        assertTrue(block.contains("• Готово"))
        assertFalse(block.contains("Приложение:"))
        assertFalse(block.contains("Активность:"))
    }

    @Test
    fun labelsAreDeduplicatedAndBounded() {
        val labels = listOf("Одна", "Одна", "Две") + (1..20).map { "Пункт $it" }
        val block = OverlayScreenContext.format(ScreenContext(packageName = "p", labels = labels))!!
        assertEquals(1, Regex("• Одна").findAll(block).count())
        // The cap keeps the block small even for a long screen.
        assertTrue(Regex("• ").findAll(block).count() <= OverlayScreenContext.MAX_LABELS)
    }

    @Test
    fun seedMessageAppendsTheBlockAfterTheQuestion() {
        val seeded = OverlayScreenContext.seedMessage(
            "Что тут написано?",
            OverlayScreenContext.format(ScreenContext(packageName = "com.example"))!!,
        )
        assertTrue(seeded.startsWith("Что тут написано?\n\n${OverlayScreenContext.HEADER}\n"))
        assertTrue(seeded.contains("Приложение: com.example"))
    }

    @Test
    fun seedMessageWithoutBlockIsJustTheQuestion() {
        assertEquals("просто вопрос", OverlayScreenContext.seedMessage("  просто вопрос  ", null))
        assertEquals("просто вопрос", OverlayScreenContext.seedMessage("просто вопрос", "   "))
    }

    @Test
    fun seedMessageKeepsTheHonestUnavailableNote() {
        val seeded = OverlayScreenContext.seedMessage(
            "что на экране?",
            OverlayScreenContext.UNAVAILABLE_ACCESSIBILITY,
        )
        assertTrue(seeded.startsWith("что на экране?\n\n"))
        assertTrue(seeded.contains(OverlayScreenContext.HEADER))
        assertTrue(seeded.contains("выключена"))
    }
}
