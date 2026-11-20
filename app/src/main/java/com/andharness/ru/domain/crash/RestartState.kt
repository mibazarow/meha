package com.andharness.ru.domain.crash

/**
 * Persisted restart-loop guard state (update.md §1.2, table `restart_state`).
 *
 * [restartCount] counts restarts inside the rolling window that started at [windowStartAt];
 * [lastRestartAt] is used to detect a "healthy" uptime and reset the window early.
 * The counting/backoff rules themselves live in the pure [com.andharness.ru.data.crash.RestartPolicy].
 */
data class RestartState(
    val restartCount: Int = 0,
    val windowStartAt: Long = 0L,
    val lastRestartAt: Long = 0L
)
