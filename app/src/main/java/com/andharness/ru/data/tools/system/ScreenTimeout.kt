package com.andharness.ru.data.tools.system

/**
 * Pure conversion helpers for the `screen_timeout_set` tool: seconds -> milliseconds for
 * `Settings.System.SCREEN_OFF_TIMEOUT`.
 *
 * The setting is stored as an `int` (milliseconds) and must be written with
 * [android.provider.Settings.System.putInt] (the value is converted to a string internally).
 * A value above [Int.MAX_VALUE] therefore cannot be represented, so requests are clamped
 * instead of overflowing. The previous `(seconds * 1000).toInt()` truncated the Int
 * multiplication and produced negative values (e.g. 2_592_000 s -> -1702967296), which
 * `Settings.System` then rejected as `Invalid value`.
 *
 * References (official Android docs):
 * - `Settings.System.SCREEN_OFF_TIMEOUT` — "The amount of time in milliseconds before the device
 *   goes to sleep ...". Bounded by `DevicePolicyManager.setMaximumTimeToLock`:
 *   https://developer.android.com/reference/android/provider/Settings.System#SCREEN_OFF_TIMEOUT
 * - `Settings.System.putInt(ContentResolver, String, int)`:
 *   https://developer.android.com/reference/android/provider/Settings.System#putInt(android.content.ContentResolver,%20java.lang.String,%20int)
 */

/** Largest whole number of seconds whose millisecond value still fits in [Int.MAX_VALUE]. */
internal const val MAX_SCREEN_TIMEOUT_SECONDS: Long = Int.MAX_VALUE.toLong() / 1000L

/**
 * Converts [seconds] to screen-timeout milliseconds.
 *
 * The multiplication is always done in `Long`, so it can never overflow. Non-positive input maps
 * to 1 ms; anything above [MAX_SCREEN_TIMEOUT_SECONDS] is clamped to [Int.MAX_VALUE].
 */
internal fun screenTimeoutMillisFromSeconds(seconds: Long): Int {
    if (seconds <= 0L) return 1
    if (seconds > MAX_SCREEN_TIMEOUT_SECONDS) return Int.MAX_VALUE
    return (seconds * 1000L).toInt()
}

/** True when [seconds] exceeds what `SCREEN_OFF_TIMEOUT` can store and will be clamped. */
internal fun screenTimeoutClamped(seconds: Long): Boolean =
    seconds > MAX_SCREEN_TIMEOUT_SECONDS
