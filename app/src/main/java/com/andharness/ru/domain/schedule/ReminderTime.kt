package com.andharness.ru.domain.schedule

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure parser of the time argument of the one-shot `reminder_set` tool.
 *
 * Two independent forms are supported:
 *
 * **Relative `delay`** ([fromDelay]) — a positive number with an optional single unit suffix,
 * case-insensitive:
 * - `s` — seconds, e.g. `30s`;
 * - `m` — minutes, e.g. `5m`;
 * - `h` — hours, e.g. `2h`;
 * - `d` — days, e.g. `1d`;
 * - no suffix — **minutes**, which is what «напомни через 5 минут» collapses to (`"5"` = 5 minutes).
 *
 * Combined units (`1h30m`) are intentionally not accepted. Blank, zero, negative, malformed and
 * values above [MAX_DELAY_DAYS] are rejected with an [Result.Error].
 *
 * **Absolute `at`** ([fromAt]) — mirrors `MessageScheduleTime`:
 * - `HH:MM` (24h, local to [ZoneId]) → the **next** occurrence: today if still in the future,
 *   otherwise tomorrow;
 * - epoch — a value below [SECONDS_UPPER_BOUND] is read as **seconds** and multiplied by 1000,
 *   anything larger is read as **milliseconds** (so 10-digit inputs are seconds and 13+-digit
 *   inputs are millis).
 *
 * Android-free and deterministic: the caller injects the current time and zone.
 * References used for the date/time semantics:
 * - `java.time` overview (authoritative for `ZoneId` / `LocalTime` / `Instant`):
 *   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/package-summary.html
 * - `LocalTime` (24-hour `HH:MM`, range 00:00–23:59):
 *   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/LocalTime.html
 */
object ReminderTime {

    /** Outcome of a parse: an absolute target in epoch millis, or a user-facing Russian error. */
    sealed interface Result {
        data class Ok(val atMs: Long) : Result
        data class Error(val message: String) : Result
    }

    /**
     * Values below this are interpreted as epoch **seconds**, not milliseconds — 10^11 ms ≈ 1973,
     * so anything smaller cannot be a plausible millisecond timestamp. Kept identical to
     * `MessageScheduleTime.SECONDS_UPPER_BOUND` so both parsers agree.
     */
    const val SECONDS_UPPER_BOUND = 100_000_000_000L

    /** Longest accepted relative delay, in days. */
    const val MAX_DELAY_DAYS = 365L

    /** Longest accepted relative delay, in milliseconds (365 days). */
    const val MAX_DELAY_MILLIS = MAX_DELAY_DAYS * 24L * 60L * 60L * 1000L

    private const val SECOND_MILLIS = 1_000L
    private const val MINUTE_MILLIS = 60L * SECOND_MILLIS
    private const val HOUR_MILLIS = 60L * MINUTE_MILLIS
    private const val DAY_MILLIS = 24L * HOUR_MILLIS

    private const val HINT = "Примеры: 30s, 5m, 2h, 1d или просто число минут (5)."

    private val DELAY = Regex("^(\\d{1,9})\\s*([smhdSMHD]?)$")
    private val CLOCK = Regex("^(\\d{1,2}):(\\d{2})$")

    /**
     * Parses a relative [delay] into an absolute epoch-millis instant [nowMillis] + the delay.
     * See the type KDOC for the accepted suffixes; a bare number means minutes.
     */
    fun fromDelay(delay: String?, nowMillis: Long): Result {
        val trimmed = delay?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return Result.Error("Не понял задержку: пусто. $HINT")
        }

        val match = DELAY.matchEntire(trimmed)
            ?: return Result.Error("Не понял задержку «$trimmed». $HINT")

        val amount = match.groupValues[1].toLongOrNull()
            ?: return Result.Error("Не понял задержку «$trimmed». $HINT")
        if (amount <= 0L) {
            return Result.Error("Задержка должна быть больше нуля. $HINT")
        }

        val unitMillis = when (match.groupValues[2].lowercase()) {
            "", "m" -> MINUTE_MILLIS
            "s" -> SECOND_MILLIS
            "h" -> HOUR_MILLIS
            "d" -> DAY_MILLIS
            else -> return Result.Error("Не понял единицу задержки «$trimmed». $HINT")
        }

        val delta = amount * unitMillis
        if (delta > MAX_DELAY_MILLIS) {
            return Result.Error("Слишком большая задержка: максимум $MAX_DELAY_DAYS дней.")
        }
        return Result.Ok(nowMillis + delta)
    }

    /**
     * Parses an absolute [at] into epoch millis relative to [nowMillis] and [zone]. See the type
     * KDOC for the accepted forms; a malformed value or a non-positive result is an error.
     */
    fun fromAt(at: String?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Result {
        val trimmed = at?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return Result.Error("Не понял время: пусто. Передай epoch millis или HH:MM (например 09:30).")
        }

        CLOCK.matchEntire(trimmed)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull()
                ?: return Result.Error("Не понял время «$trimmed». Передай epoch millis или HH:MM (например 09:30).")
            val minute = match.groupValues[2].toIntOrNull()
                ?: return Result.Error("Не понял время «$trimmed». Передай epoch millis или HH:MM (например 09:30).")
            if (hour !in 0..23 || minute !in 0..59) {
                return Result.Error("Не понял время «$trimmed». Передай epoch millis или HH:MM (например 09:30).")
            }
            return Result.Ok(nextOccurrence(hour, minute, nowMillis, zone))
        }

        val epoch = trimmed.toLongOrNull()
            ?: return Result.Error("Не понял время «$trimmed». Передай epoch millis или HH:MM (например 09:30).")
        if (epoch <= 0L) {
            return Result.Error("Время должно быть больше нуля. Передай epoch millis или HH:MM (например 09:30).")
        }
        return Result.Ok(if (epoch < SECONDS_UPPER_BOUND) epoch * 1000L else epoch)
    }

    /** The next local [hour]:[minute] strictly after [nowMillis]; tomorrow when today's has passed. */
    private fun nextOccurrence(hour: Int, minute: Int, nowMillis: Long, zone: ZoneId): Long {
        val time = LocalTime.of(hour, minute)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val candidate = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (candidate > nowMillis) return candidate
        return today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
    }
}
