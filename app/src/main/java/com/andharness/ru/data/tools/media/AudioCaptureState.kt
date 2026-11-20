package com.andharness.ru.data.tools.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide (singleton) state of the app-playback audio capture, shared between the foreground
 * service that actually records ([com.andharness.ru.bg.AudioCaptureService]) and the
 * `audio_capture_app` tool that drives it.
 *
 * A [StateFlow] lets the tool `first { it.finished }`-await the result instead of polling.
 * Reference:
 * https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
 */
object AudioCaptureState {

    /** Outcome of one capture session. [finished] flips to true only when the session is over. */
    data class Status(
        val finished: Boolean = false,
        val path: String? = null,
        val bytes: Long = 0L,
        /** Root-mean-square level in dBFS; -100 when the captured stream was empty. */
        val rmsDbfs: Double = -100.0,
        /** True when the captured signal stayed below the silence threshold the whole window. */
        val silent: Boolean = false,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Called by the service once the audio track is actually being read. */
    internal fun markStarted() {
        _status.value = Status(finished = false)
    }

    /** Called by the service when capture ends (or fails); [error] is an honest reason. */
    internal fun markFinished(
        path: String?,
        bytes: Long,
        rmsDbfs: Double,
        silent: Boolean,
        error: String? = null,
    ) {
        _status.value = Status(
            finished = true,
            path = path,
            bytes = bytes,
            rmsDbfs = rmsDbfs,
            silent = silent,
            error = error,
        )
    }

    /** Clears the slot before a new session so a stale outcome is never reported as the current one. */
    internal fun reset() {
        _status.value = Status()
    }
}
