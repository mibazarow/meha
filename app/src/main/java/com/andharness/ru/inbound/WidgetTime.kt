package com.andharness.ru.inbound

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Compact "when was this" label for the home-screen widget chat rows.
 *
 * Deliberately pure and Android-free (no `Context`, no `android.text.format.DateUtils`) so it can be
 * unit-tested on the JVM with an injected clock and zone — the widget calls it with
 * `System.currentTimeMillis()` and the device zone, tests pin both.
 *
 * Rules (all calendar-day based, not raw 24h deltas, so "вчера" means yesterday's date):
 * - today (or any same-day / clock-skewed future value) → `HH:mm`, e.g. `12:34`;
 * - yesterday → `вчера`;
 * - 2–6 days ago → `N дн`, e.g. `3 дн`;
 * - anything older → `dd.MM.yy`, e.g. `17.09.26`.
 *
 * `java.time` on Android is available natively from API 26 (this app's `minSdk` is 31):
 * https://developer.android.com/reference/java/time/Instant
 */
internal fun formatWidgetTime(
    updatedAt: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val updated = Instant.ofEpochMilli(updatedAt).atZone(zone)
    val current = Instant.ofEpochMilli(now).atZone(zone)
    val days = ChronoUnit.DAYS.between(updated.toLocalDate(), current.toLocalDate())
    return when {
        days <= 0L -> TIME_FORMAT.format(updated)
        days == 1L -> "вчера"
        days < 7L -> "$days дн"
        else -> DATE_FORMAT.format(updated)
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.ROOT)
