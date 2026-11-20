package com.andharness.ru.data.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object WatchdogState {

    const val REASON_RUNNING = "running"
    const val REASON_NOT_STARTED = "not_started"
    const val REASON_DISABLED = "disabled"
    const val REASON_NO_FEATURES = "no_features"
    const val REASON_CRASHED_LIMIT = "crashed_limit"
    const val REASON_START_FAILED = "start_failed"
    const val REASON_STOPPED = "stopped"

    private val _status = MutableStateFlow(WatchdogStatus())
    val status: StateFlow<WatchdogStatus> = _status.asStateFlow()

    fun markRunning(reason: String = REASON_RUNNING, startedAt: Long = System.currentTimeMillis()) {
        _status.update { WatchdogStatus(running = true, reason = reason, startedAt = startedAt) }
    }

    fun markStopped(reason: String) {
        _status.update { current ->
            WatchdogStatus(running = false, reason = reason, startedAt = current.startedAt)
        }
    }

    /** Test/reset hook: restores the initial "not started" state. */
    fun reset() {
        _status.update { WatchdogStatus() }
    }
}
