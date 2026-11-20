package com.andharness.ru.domain.ui

/**
 * Pure, device-free policy for every tool that asks the UI to run an interactive system flow
 * (a consent dialog, a picker or a role request) and then waits for its result.
 *
 * The wait for the user's answer happens while a caller-hosted broadcast may still be open (the
 * debug bridge uses `BroadcastReceiver.goAsync()`). Android bounds that window: ~10 s for a
 * foreground-priority broadcast and ~60 s (up to 120 s under CPU starvation on Android 14+) for a
 * background-priority one, and `PendingResult.finish()` must run inside it or the app ANRs —
 * https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()
 * https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs
 *
 * The UI wait therefore must fit inside the shortest documented window, and it must be skipped
 * completely when no foreground Activity can host the dialog. Keeping the rule in a plain object
 * (no Android API) lets both be unit-tested on the JVM.
 */
object UiConsentPolicy {

    /**
     * Bound on the UI wait in milliseconds. Deliberately well under
     * [BROADCAST_ANR_WINDOW_MS] so a broadcast-hosted caller releases the broadcast before Android
     * considers it unresponsive.
     */
    const val TIMEOUT_MS = 45_000L

    /** Shortest documented broadcast receive timeout: 60 s for a background-priority intent. */
    const val BROADCAST_ANR_WINDOW_MS = 60_000L

    /**
     * True when [timeoutMs] leaves margin under the shortest documented broadcast ANR window, so
     * waiting that long can never hold a `goAsync()` pending result past the timeout.
     */
    fun isWithinAnrWindow(timeoutMs: Long = TIMEOUT_MS): Boolean =
        timeoutMs > 0L && timeoutMs < BROADCAST_ANR_WINDOW_MS

    /**
     * True only when a foreground Activity can actually host the interactive system flow. A
     * backgrounded app has no Activity to launch it from, so waiting would only burn the whole
     * timeout and then ANR the broadcast — the honest answer is to refuse immediately.
     */
    fun canAwaitUi(appForeground: Boolean): Boolean = appForeground

    /** Honest failure returned when no foreground Activity can show the system dialog. */
    const val NO_UI_MESSAGE =
        "Системный диалог подтверждения можно показать только когда приложение на переднем плане. " +
            "Откройте Меху и повторите запрос."
}
