package com.andharness.ru.domain.message

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure parser of the `at` argument of `schedule_message` (update.md §4.8).
 *
 * Two accepted forms:
 * - **`HH:MM`** (local, e.g. `09:30`) → the **next** occurrence of that wall-clock time: today when
 *   it is still in the future, otherwise tomorrow;
 * - **epoch** → milliseconds since the Unix epoch; a value that is far too small to be milliseconds
 *   (below [SECONDS_UPPER_BOUND]) is treated as **seconds** and converted, which is what most APIs
 *   return and is the honest interpretation of e.g. `1730000000`.
 *
 * Anything else, a non-positive epoch or an impossible clock time returns `null`, so the caller can
 * report the parse failure instead of guessing a time.
 *
 * Android-free and unit-testable on the JVM; the [now] instant is injected for determinism.
 * Reference: https://developer.android.com/reference/java/time/Instant
 */
object MessageScheduleTime {

    /**
     * Values below this are interpreted as epoch **seconds**, not milliseconds (10^11 ms ≈ 1973,
     * so anything smaller cannot be a plausible millisecond timestamp).
     */
    const val SECONDS_UPPER_BOUND = 100_000_000_000L

    private val CLOCK = Regex("^(\\d{1,2}):(\\d{2})$")

    /** Parses [raw] into epoch millis relative to [now], or `null` when it is not a valid time. */
    fun parse(raw: String?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        CLOCK.matchEntire(trimmed)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return nextOccurrence(hour, minute, now, zone)
        }

        val epoch = trimmed.toLongOrNull() ?: return null
        if (epoch <= 0L) return null
        return if (epoch < SECONDS_UPPER_BOUND) epoch * 1000L else epoch
    }

    /** The next local [hour]:[minute] strictly after [now]. */
    private fun nextOccurrence(hour: Int, minute: Int, now: Long, zone: ZoneId): Long {
        val time = LocalTime.of(hour, minute)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val candidate = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (candidate > now) return candidate
        return today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
    }
}
