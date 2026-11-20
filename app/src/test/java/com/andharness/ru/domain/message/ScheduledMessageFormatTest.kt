package com.andharness.ru.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the Android-free helpers behind `schedule_message_list` (BUG-018):
 * whitespace-collapsing/truncating text preview and the pending/overdue status mapping.
 */
class ScheduledMessageFormatTest {

    @Test
    fun previewKeepsShortTextUnchanged() {
        assertEquals("Привет", ScheduledMessageFormat.preview("Привет"))
    }

    @Test
    fun previewCollapsesWhitespaceAndNewlines() {
        assertEquals("a b c", ScheduledMessageFormat.preview("  a\n\tb   c  "))
    }

    @Test
    fun previewTruncatesLongTextWithEllipsis() {
        val text = "x".repeat(100)
        val preview = ScheduledMessageFormat.preview(text, maxLength = 10)
        assertEquals(10, preview.length)
        assertTrue(preview.endsWith("…"))
        assertEquals("xxxxxxxxx…", preview)
    }

    @Test
    fun previewHandlesTinyMaxLength() {
        assertEquals("…", ScheduledMessageFormat.preview("abcdef", maxLength = 1))
    }

    @Test
    fun statusIsPendingWhileInTheFuture() {
        assertEquals(
            ScheduledMessageStatus.PENDING,
            ScheduledMessageFormat.status(scheduledAt = 2_000L, now = 1_000L),
        )
    }

    @Test
    fun statusIsOverdueAtOrAfterScheduledTime() {
        assertEquals(
            ScheduledMessageStatus.OVERDUE,
            ScheduledMessageFormat.status(scheduledAt = 1_000L, now = 1_000L),
        )
        assertEquals(
            ScheduledMessageStatus.OVERDUE,
            ScheduledMessageFormat.status(scheduledAt = 1_000L, now = 2_000L),
        )
    }

    @Test
    fun statusLabelsAreHumanReadable() {
        assertEquals("ожидает", ScheduledMessageFormat.statusLabel(2_000L, 1_000L))
        assertTrue(ScheduledMessageFormat.statusLabel(1_000L, 2_000L).contains("просрочено"))
    }
}
