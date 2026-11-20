package com.andharness.ru.domain.schedule

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure decision/formatting helper for one scheduled run ([com.andharness.ru.bg.ScheduleWorker]).
 *
 * Bug #4: the scheduled run used to write into a THROWAWAY chat while the schedule's own chat stayed
 * empty and the notification deep-linked to that empty chat. These functions encode the corrected
 * policy (run in the originating chat, always carry the current time, report failures honestly) and
 * are Android-free so they can be unit-tested on the JVM.
 *
 * References:
 * - https://developer.android.com/reference/java/time/format/DateTimeFormatter
 * - https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started
 */
object ScheduleRunPlan {

    /**
     * The chat a run must write into: prefer the chat the schedule was created from when it is a
     * valid id, otherwise the freshly created fallback chat. `null` means neither exists.
     */
    fun targetChatId(originatingChatId: Long?, fallbackChatId: Long?): Long? =
        originatingChatId?.takeIf { it > 0L } ?: fallbackChatId?.takeIf { it > 0L }

    /**
     * The user-turn text of a scheduled run: the stored prompt plus the CURRENT local date/time
     * (so the agent never has to guess "now" when a schedule fires).
     */
    fun runMessage(prompt: String, nowMillis: Long, zone: ZoneId, locale: Locale): String {
        val zoned = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val day = zoned.format(DateTimeFormatter.ofPattern("EEEE", locale))
        val date = zoned.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", locale))
        val time = zoned.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        return "[Задача по расписанию. Сейчас $day, $date, $time (${zone.id}).]\n\n${prompt.trim()}"
    }

    /** An honest failure body written into the schedule's chat when the run produced no answer. */
    fun failureMessage(reason: String?): String =
        "Не получилось выполнить задачу по расписанию: ${reasonOrUnknown(reason)}"

    /**
     * An honest failure body written into the chat of a ONE-SHOT reminder when its run produced no
     * answer. Same policy as [failureMessage], worded as a reminder so the user is not told a
     * "schedule" failed when they only asked to be reminded of something.
     */
    fun reminderFailureMessage(reason: String?): String =
        "Не получилось выполнить напоминание: ${reasonOrUnknown(reason)}"

    /** Notification text for a finished recurring run; [reason] is non-null when it failed. */
    fun notificationText(reason: String?): String =
        if (reason == null) {
            "Задача по расписанию выполнена"
        } else {
            "Задача по расписанию не выполнена: $reason"
        }

    /**
     * Notification text for a fired ONE-SHOT reminder ([ScheduledTask.isOneShot]); [reason] is
     * non-null when the run failed. On success it repeats the stored [prompt] (truncated) so the
     * user actually sees WHAT they asked to be reminded of, not just that something fired.
     */
    fun reminderNotificationText(prompt: String, reason: String?): String {
        if (reason != null) return "Напоминание не выполнено: $reason"
        val text = prompt.trim().replace('\n', ' ').take(REMINDER_TEXT_LIMIT)
        return if (text.isEmpty()) "Напоминание сработало" else "Напоминание: $text"
    }

    /**
     * Notification text for a schedule/reminder that could NOT be run when it was due and is
     * delivered LATE instead — the device was off/dozing, the power guard deferred the heavy agent
     * run, or the agent/chat path was unavailable. The wording must make the delay explicit; it must
     * never read like a normal on-time firing, otherwise the user would not understand why it
     * arrived now. Used by [com.andharness.ru.data.schedule.ScheduleCatchUp] and by
     * [com.andharness.ru.bg.ScheduleWorker] when it cannot resolve or create a target chat.
     */
    fun missedRunNotificationText(prompt: String, isOneShot: Boolean, reason: String?): String {
        val head = if (isOneShot) {
            val text = prompt.trim().replace('\n', ' ').take(REMINDER_TEXT_LIMIT)
            if (text.isEmpty()) "Пропущенное напоминание" else "Пропущенное напоминание: $text"
        } else {
            "Пропущенный запуск по расписанию"
        }
        val why = reason?.trim().takeUnless { it.isNullOrEmpty() }
        return if (why == null) "$head (доставлено с задержкой)" else "$head — доставлено с задержкой: $why"
    }

    /** Single source of the "why did it fail" wording, shared by every failure body above. */
    private fun reasonOrUnknown(reason: String?): String =
        reason?.trim().takeUnless { it.isNullOrEmpty() } ?: "неизвестная ошибка"

    /** Upper bound of the reminder prompt echoed into a notification; longer text is cut. */
    private const val REMINDER_TEXT_LIMIT = 120
}
