package com.andharness.ru.domain.notifications

import kotlinx.serialization.Serializable

/**
 * A local-time quiet window as minutes since midnight (0..1439), so the model serializes as plain
 * integers and cannot depend on a time zone. When [startMinute] > [endMinute] the window wraps past
 * midnight (e.g. 23:00–07:00); when they are equal the window is empty (never quiet) — a deliberate
 * choice so a default/zeroed window can never mute the whole day.
 */

@Serializable
data class QuietHours(
    val startMinute: Int,
    val endMinute: Int,
) {
    init {
        require(startMinute in 0..MINUTES_PER_DAY - 1) { "startMinute вне 0..1439: $startMinute" }
        require(endMinute in 0..MINUTES_PER_DAY - 1) { "endMinute вне 0..1439: $endMinute" }
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        /** Builds a window from wall-clock hour/minute, coercing each field into range. */
        fun of(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): QuietHours = QuietHours(
            startMinute = startHour.coerceIn(0, 23) * 60 + startMinute.coerceIn(0, 59),
            endMinute = endHour.coerceIn(0, 23) * 60 + endMinute.coerceIn(0, 59),
        )
    }
}
