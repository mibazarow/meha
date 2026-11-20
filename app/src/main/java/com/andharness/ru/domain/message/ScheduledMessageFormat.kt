package com.andharness.ru.domain.message

import com.andharness.ru.domain.formatting.collapseWhitespace

/**
 * Pure, Android-free presentation helpers for the deferred-message tools
 * `schedule_message_list` / `schedule_message_cancel` (BUG-018).
 *
 * Keeping the preview/status mapping here makes it unit-testable on the JVM; the handlers in
 * `PersonToolsHandler` only feed it stored values and format the result.
 */
object ScheduledMessageFormat {

    /** Maximum length of the text preview shown by `schedule_message_list`. */
    const val PREVIEW_MAX_LENGTH = 60

    private const val ELLIPSIS = "…"

    /**
     * Collapses runs of whitespace/newlines and truncates [text] to [maxLength] characters,
     * appending an ellipsis when it was cut. The body is only previewed, never stored again.
     */
    fun preview(text: String, maxLength: Int = PREVIEW_MAX_LENGTH): String {
        val normalized = collapseWhitespace(text)
        if (normalized.length <= maxLength) return normalized
        val keep = (maxLength - ELLIPSIS.length).coerceAtLeast(0)
        return normalized.take(keep) + ELLIPSIS
    }

    /**
     * A deferred message is still [ScheduledMessageStatus.PENDING] while its time is in the future
     * and [ScheduledMessageStatus.OVERDUE] once that time has passed (the worker has not removed it
     * yet, so it is either about to fire or is waiting on the scheduler).
     */
    fun status(scheduledAt: Long, now: Long): ScheduledMessageStatus =
        if (scheduledAt <= now) ScheduledMessageStatus.OVERDUE else ScheduledMessageStatus.PENDING

    /** Short human label of [status] for the list output. */
    fun statusLabel(scheduledAt: Long, now: Long): String = when (status(scheduledAt, now)) {
        ScheduledMessageStatus.PENDING -> "ожидает"
        ScheduledMessageStatus.OVERDUE -> "просрочено (сработает или ждёт планировщик)"
    }
}
