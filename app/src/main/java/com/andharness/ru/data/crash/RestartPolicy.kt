package com.andharness.ru.data.crash

import com.andharness.ru.domain.crash.RestartState

/**
 * Pure, unit-testable crash-restart loop guard (update.md §1.2): at most
 * [MAX_RESTARTS_PER_WINDOW] restarts inside a rolling [WINDOW_MS] window, with exponential backoff
 * [BACKOFF_MS]. The window is reset early when the app stayed alive longer than
 * [HEALTHY_UPTIME_MS] after the previous restart ("healthy uptime").
 *
 * No Android or Room types are used, so this logic is fully testable on the JVM.
 */
object RestartPolicy {

    /** Rolling window: one hour. */
    const val WINDOW_MS = 60L * 60 * 1000

    /** Maximum restarts inside one rolling window: 3. */
    const val MAX_RESTARTS_PER_WINDOW = 3

    /** Uptime without a crash that counts as "healthy" and resets the counter: 5 minutes. */
    const val HEALTHY_UPTIME_MS = 5L * 60 * 1000

    /** Exponential backoff for restart #1, #2, #3: 5 s → 30 s → 2 min. */
    val BACKOFF_MS = longArrayOf(5_000L, 30_000L, 120_000L)

    /** Backoff for the given restart number (1-based); clamped to the defined ladder. */
    fun backoffMs(restartNumber: Int): Long {
        if (restartNumber <= 0) return BACKOFF_MS.first()
        val index = minOf(restartNumber, BACKOFF_MS.size) - 1
        return BACKOFF_MS[index]
    }

    /** True when the current window has fully elapsed and a new one should start. */
    fun isWindowExpired(state: RestartState, now: Long): Boolean =
        state.windowStartAt <= 0L || now - state.windowStartAt >= WINDOW_MS

    /** True when the app ran long enough after the last restart to be considered healthy. */
    fun isHealthyUptime(state: RestartState, now: Long): Boolean =
        state.lastRestartAt > 0L && now - state.lastRestartAt >= HEALTHY_UPTIME_MS

    /**
     * Counts one crash and decides whether a restart is still allowed.
     *
     * Resets the counter first when the window expired or the previous run was healthy. The returned
     * [RestartDecision.state] is the state that must be persisted; [RestartDecision.state] is
     * [RestartState] and the caller is responsible for saving it.
     */
    fun onCrash(state: RestartState, now: Long): RestartDecision {
        val reset = isWindowExpired(state, now) || isHealthyUptime(state, now)
        val windowStartAt = if (reset) now else state.windowStartAt
        val count = (if (reset) 0 else state.restartCount) + 1
        val newState = RestartState(
            restartCount = count,
            windowStartAt = windowStartAt,
            lastRestartAt = now
        )
        val allowed = count <= MAX_RESTARTS_PER_WINDOW
        return RestartDecision(
            allowed = allowed,
            delayMs = if (allowed) backoffMs(count) else 0L,
            restartNumber = count,
            state = newState,
            limitReached = !allowed
        )
    }
}
