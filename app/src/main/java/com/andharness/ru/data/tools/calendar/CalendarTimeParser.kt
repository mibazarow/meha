package com.andharness.ru.data.tools.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Shared parser for calendar date/time tool arguments. Always returns epoch milliseconds.
 *
 * Accepted shapes:
 * 1. epoch millis (`1788246000000`),
 * 2. date-only `YYYY-MM-DD` — interpreted in [zone] at start-of-day, or end-of-day
 *    (`23:59:59.999999999`) when [endOfDay] is true,
 * 3. ISO-8601 date-time: `2026-09-01T10:00:00Z`, `2026-09-01T10:00:00+03:00`, or a naive
 *    local date-time `2026-09-01T10:00` (interpreted in [zone]).
 *
 * A genuinely malformed value raises [IllegalArgumentException] with a clear message.
 *
 * References:
 * - `LocalDate.parse` / `LocalDateTime.parse` (ISO_LOCAL_DATE / ISO_LOCAL_DATE_TIME):
 *   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/LocalDate.html#parse(java.lang.CharSequence)
 *   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/LocalDateTime.html#parse(java.lang.CharSequence)
 * - `Instant.parse` (ISO_INSTANT): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Instant.html#parse(java.lang.CharSequence)
 * - `OffsetDateTime.parse` (ISO_OFFSET_DATE_TIME, offsets with or without seconds):
 *   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/OffsetDateTime.html#parse(java.lang.CharSequence)
 * - `ZoneId.systemDefault()`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZoneId.html#systemDefault()
 */
object CalendarTimeParser {

    fun parse(raw: String, endOfDay: Boolean = false, zone: ZoneId = ZoneId.systemDefault()): Long {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Пустое значение даты.")
        }
        trimmed.toLongOrNull()?.let { return it }

        try {
            return LocalDate.parse(trimmed)
                .atTime(if (endOfDay) LocalTime.MAX else LocalTime.MIDNIGHT)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Not a date-only value; try the date-time shapes below.
        }

        try {
            return Instant.parse(trimmed).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Not an instant with 'Z'/offset; try the remaining shapes below.
        }

        try {
            return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Not an offset date-time; try the naive local shape below.
        }

        try {
            return LocalDateTime.parse(trimmed).atZone(zone).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Fall through to the honest error below.
        }

        throw IllegalArgumentException(
            "Некорректная дата: «$raw». Ожидается epoch millis, YYYY-MM-DD " +
                "или ISO-8601 (например, 2026-09-01T10:00:00+03:00).",
        )
    }
}
