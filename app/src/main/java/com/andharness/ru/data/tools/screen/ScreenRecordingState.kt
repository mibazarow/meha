package com.andharness.ru.data.tools.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide (singleton) state of the screen recorder, shared between the foreground service that
 * actually records ([com.andharness.ru.bg.ScreenRecordService]) and the tools that drive it
 * (`screen_record_start` / `screen_record_stop` / `screen_record_status`).
 *
 * A `StateFlow` is used so a tool can `first { }`-await the start/stop transition instead of
 * polling. Reference:
 * https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
 */
object ScreenRecordingState {

    /** Current recording status: whether it is [active], the [path] and when it [startedAtEpochMs]. */
    data class Status(
        val active: Boolean = false,
        val path: String? = null,
        val startedAtEpochMs: Long = 0L,
        val withAudio: Boolean = false,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Called by the service once the recorder and virtual display are actually running. */
    internal fun markStarted(path: String, withAudio: Boolean) {
        _status.value = Status(
            active = true,
            path = path,
            startedAtEpochMs = System.currentTimeMillis(),
            withAudio = withAudio,
            error = null,
        )
    }

    /** Called by the service when recording stops (or fails to start); [error] is an honest reason. */
    internal fun markStopped(error: String? = null) {
        val previous = _status.value
        _status.value = Status(
            active = false,
            path = previous.path,
            startedAtEpochMs = previous.startedAtEpochMs,
            withAudio = previous.withAudio,
            error = error,
        )
    }

    /** Clears the slot before a new session so a stale error is never reported as the current one. */
    internal fun reset() {
        _status.value = Status()
    }
}
