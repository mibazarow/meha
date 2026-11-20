package com.andharness.ru.domain.agent

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders the CURRENT local date, time, day of week and timezone as a system-prompt block.
 *
 * The agent has no clock of its own: without this block it answers "what time is it" from its
 * training data (the observed ~13-minute error) and refuses to compute alarm times. The block is
 * therefore rendered FRESH on every request by [com.andharness.ru.data.agent.ContextManagerImpl]
 * (never built once and cached), in the user's locale and timezone.
 *
 * Android-free and unit-testable on the JVM; the instant/zone/locale are parameters, so the tests
 * drive a deterministic pseudo-clock.
 *
 * References:
 * - https://developer.android.com/reference/java/time/format/DateTimeFormatter
 * - https://developer.android.com/reference/java/time/ZoneId
 */
object CurrentTimeContext {

    /**
     * Formats a compact instruction block for the given instant. Pure.
     *
     * The block is deliberately stable WITHIN one minute: the wall clock is minute-granular and the
     * epoch value is floored to the start of the minute. Two turns inside the same minute therefore
     * render byte-identical text. This block rides in the runtime tail; a tail that only changes
     * when the minute changes keeps the provider prefix cache warm instead of invalidating it on
     * every turn for a value the model cannot use anyway (it only ever sees `HH:mm`).
     */
    fun render(nowMillis: Long, zone: ZoneId, locale: Locale): String {
        val stableMillis = nowMillis - Math.floorMod(nowMillis, MILLIS_PER_MINUTE)
        val instant = Instant.ofEpochMilli(stableMillis)
        val zoned = instant.atZone(zone)
        val dayOfWeek = zoned.format(DateTimeFormatter.ofPattern("EEEE", locale))
        val date = zoned.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", locale))
        val time = zoned.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        return buildString {
            appendLine("CURRENT DATE AND TIME (authoritative, refreshed on every request — always trust")
            appendLine("this over your own assumptions or training data):")
            appendLine("- Now: $dayOfWeek, $date, $time")
            appendLine("- Timezone: ${zone.id} (UTC${offsetText(zone.rules.getOffset(instant))})")
            appendLine("- Unix epoch milliseconds (start of this minute): $stableMillis")
            append("Use THIS value for any \"now\"/\"today\"/\"tomorrow\"/\"in N minutes\" calculation ")
            append("(alarms, timers, schedules), and if the user asks for the time or date, answer with it directly — never guess.")
        }
    }

    private fun offsetText(offset: ZoneOffset): String =
        if (offset == ZoneOffset.UTC) "+00:00" else offset.id

    /** One minute in milliseconds; the granularity the block is stable at. */
    private const val MILLIS_PER_MINUTE = 60_000L
}
