package com.andharness.ru.data.tools.communications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of the Android-free `messages_summarize` helpers: window parsing, bounded selection /
 * character-budget trimming and the honest empty/prompt rendering.
 */
class MessagesSummarizeSupportTest {

    private val now = 1_700_000_000_000L
    private val day = SUMMARY_DAY_MS

    @Test
    fun parsesRelativeAndAllWindows() {
        assertEquals(now - 7 * day, parseWindowSinceMillis("7d", now, 0))
        assertEquals(now - 30 * day, parseWindowSinceMillis("30d", now, 0))
        assertEquals(0L, parseWindowSinceMillis("all", now, 0))
        assertEquals("blank defaults to 30d", now - 30 * day, parseWindowSinceMillis(null, now, 0))
        assertEquals("blank defaults to 30d", now - 30 * day, parseWindowSinceMillis("  ", now, 0))
        assertNull(parseWindowSinceMillis("bogus", now, 0))
        assertNull(parseWindowSinceMillis("0d", now, 0))
    }

    @Test
    fun todayIsStartOfLocalDay() {
        val offset = 3 * 60 * 60 * 1000L
        val since = parseWindowSinceMillis("today", now, offset)!!
        val localNow = now + offset
        val expectedLocalStart = localNow - Math.floorMod(localNow, day)
        assertEquals(expectedLocalStart - offset, since)
        assertTrue(since <= now)
    }

    @Test
    fun emptySetIsHonest() {
        val selection = selectSummaryLines(emptyList(), 40)
        assertTrue(selection.lines.isEmpty())
        val message = emptySummaryMessage(selection, topic = "Авито", personLabel = null)
        assertTrue(message.contains("Ничего не найдено"))
        assertTrue(message.contains("Авито"))
    }

    @Test
    fun trimsOldestOverCharBudget() {
        val messages = listOf(
            SummaryMessage(1, "A", "SMS", "0123456789"),
            SummaryMessage(2, "B", "SMS", "abcdefghij"),
            SummaryMessage(3, "C", "SMS", "ABCDEFGHIJ"),
        )
        val selection = selectSummaryLines(messages, limit = 10, maxChars = 20)
        assertEquals(listOf("abcdefghij", "ABCDEFGHIJ"), selection.lines.map { it.text })
        assertEquals(1, selection.droppedForLength)
        assertEquals(0, selection.skippedEmpty)
    }

    @Test
    fun honoursLimitNewestKeptAndSkipsBlank() {
        val messages = listOf(
            SummaryMessage(1, "A", "SMS", "старое"),
            SummaryMessage(2, "B", "SMS", "   "),
            SummaryMessage(3, "C", "SMS", "новое"),
        )
        val selection = selectSummaryLines(messages, limit = 2)
        assertEquals(listOf("новое"), selection.lines.map { it.text })
        assertEquals(1, selection.skippedEmpty)
        assertEquals(1, selection.droppedForLimit)
    }

    @Test
    fun clampsSummaryLimit() {
        assertEquals(DEFAULT_SUMMARY_LIMIT, clampSummaryLimit(null))
        assertEquals(1, clampSummaryLimit(0))
        assertEquals(MAX_SUMMARY_LIMIT, clampSummaryLimit(999))
    }

    @Test
    fun promptIsGroundedAndIncludesSelectedMessages() {
        val selection = selectSummaryLines(
            listOf(SummaryMessage(1, "Иван", "SMS", "Позвони вечером")),
            limit = 10,
        )
        val prompt = buildMessagesSummaryPrompt(
            selection,
            topic = "встреча",
            personLabel = "Иван",
            sourceLabel = "SMS",
            windowLabel = "30d",
        )
        assertTrue(prompt.contains("[1] Иван |"))
        assertTrue(prompt.contains("Позвони вечером"))
        assertTrue(prompt.contains("не выдумывай"))
        assertTrue(MESSAGES_SUMMARY_SYSTEM_PROMPT.contains("ТОЛЬКО на переданные"))
    }

    @Test
    fun summaryAuthorFallsBackToSource() {
        assertEquals("Иван", summaryAuthor(SummaryMessage(1, " Иван ", "SMS", "x")))
        assertEquals("MESSENGER", summaryAuthor(SummaryMessage(1, "  ", "MESSENGER", "x")))
        assertEquals("неизвестно", summaryAuthor(SummaryMessage(1, null, "", "x")))
    }

    @Test
    fun formatReportsSavedMemoryHonestly() {
        val selection = selectSummaryLines(
            listOf(SummaryMessage(1, "Иван", "SMS", "Позвони")),
            limit = 10,
        )
        val out = formatMessagesSummary(
            selection,
            topic = "встреча",
            personLabel = "Иван",
            sourceLabel = "SMS",
            windowLabel = "7d",
            summary = "Договорились созвониться.",
            savedMemoryId = 42L,
        )
        assertTrue(out.contains("Резюме сообщений"))
        assertTrue(out.contains("учтено сообщений: 1"))
        assertTrue(out.contains("Договорились созвониться."))
        assertTrue(out.contains("Сохранено в память (id 42"))
        assertTrue(out.contains(SUMMARY_TAG))
    }
}
