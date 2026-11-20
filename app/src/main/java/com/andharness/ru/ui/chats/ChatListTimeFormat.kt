package com.andharness.ru.ui.chats

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Telegram-style relative time label for a chat-list row.
 *
 * Deliberately pure and Android-free (no `Context`, no `android.text.format.DateUtils`) so it can be
 * unit-tested on the JVM with an injected clock and zone — the screen calls it with
 * `System.currentTimeMillis()` and the device zone, the test pins both. Calendar-day based (not a
 * raw 24h window), so «Вчера» always means yesterday's date.
 *
 * Rules:
 * - today, or any same-day / clock-skewed future value → `HH:mm`, e.g. `12:34`;
 * - yesterday → `Вчера`;
 * - 2–6 days ago → a short Russian weekday, e.g. `Пн`;
 * - 7 days or older → `dd.MM.yy`, e.g. `17.09.25`.
 *
 * `java.time` is available natively from API 26 (this app's `minSdk` is 31):
 * https://developer.android.com/reference/java/time/Instant
 * https://developer.android.com/reference/java/time/temporal/ChronoUnit
 */
internal fun formatChatListTime(
    epochMillis: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val current = Instant.ofEpochMilli(now).atZone(zone)
    val days = ChronoUnit.DAYS.between(moment.toLocalDate(), current.toLocalDate())
    return when {
        days <= 0L -> TIME_FORMAT.format(moment)
        days == 1L -> "Вчера"
        days < 7L -> shortWeekday(moment.dayOfWeek)
        else -> DATE_FORMAT.format(moment)
    }
}

/**
 * Inline clock label for a message bubble: always `HH:mm` in [zone], regardless of the day — the
 * chat bubble shows only the wall-clock time (Telegram-style), never «Вчера»/a date, because the
 * full date context is already carried by the surrounding history.
 *
 * Pure and Android-free so it is JVM-testable with an injected zone. `java.time` is available
 * natively from API 26 (this app's `minSdk` is 31):
 * https://developer.android.com/reference/java/time/format/DateTimeFormatter
 */
internal fun formatBubbleTime(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()
): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/**
 * Short Russian weekday label. A fixed table (indexed by [DayOfWeek.value], 1 = Monday) rather than
 * `DayOfWeek.getDisplayName` so the label does not depend on the JVM/ICU locale of the test host.
 */
private fun shortWeekday(dayOfWeek: DayOfWeek): String =
    SHORT_WEEKDAYS.getOrElse(dayOfWeek.value - 1) { "" }

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.ROOT)

// DayOfWeek: MONDAY(1) … SUNDAY(7) — https://developer.android.com/reference/java/time/DayOfWeek
private val SHORT_WEEKDAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
