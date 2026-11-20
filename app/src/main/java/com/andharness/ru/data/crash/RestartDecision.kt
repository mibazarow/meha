package com.andharness.ru.data.crash

import com.andharness.ru.domain.crash.RestartState

/** Outcome of [RestartPolicy.onCrash]: whether to restart, after what delay, and the new state. */
data class RestartDecision(
    val allowed: Boolean,
    val delayMs: Long,
    val restartNumber: Int,
    val state: RestartState,
    val limitReached: Boolean
)
