package com.andharness.ru.data.tools.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppComponentsSummaryTest {

    @Test
    fun emptyListReportsNone() {
        assertEquals("Сервисы: нет", AppComponentsSummary.format("Сервисы", emptyList()))
    }

    @Test
    fun exportedFlagAndProcessAreRendered() {
        val text = AppComponentsSummary.format(
            "Сервисы «com.x»",
            listOf(
                DeclaredComponent("com.x.A", exported = true, process = "com.x:bg"),
                DeclaredComponent("com.x.B", exported = false, process = null),
            ),
        )
        assertEquals(
            "Сервисы «com.x» (2):\n" +
                "  com.x.A [exported, процесс: com.x:bg]\n" +
                "  com.x.B [not exported]",
            text,
        )
    }

    @Test
    fun blankProcessIsOmitted() {
        val text = AppComponentsSummary.format(
            "Ресиверы",
            listOf(DeclaredComponent("com.x.R", exported = true, process = "  ")),
        )
        assertEquals("Ресиверы (1):\n  com.x.R [exported]", text)
    }

    @Test
    fun listIsCappedWithHonestTail() {
        val items = (1..AppComponentsSummary.MAX_LISTED + 3).map {
            DeclaredComponent("c$it", exported = false, process = null)
        }
        val text = AppComponentsSummary.format("Сервисы", items)
        assertTrue(text.contains("… и ещё 3"))
        assertEquals(
            AppComponentsSummary.MAX_LISTED,
            text.lines().count { it.trimStart().startsWith("c") },
        )
    }
}
