package com.andharness.ru.data.tools.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Process-wide (singleton) state of an ongoing in-process Camera2 video recording, shared between
 * the tool that starts it (`camera_video_record`) and the tool that stops it (`camera_record_stop`).
 *
 * This mirrors [com.andharness.ru.data.tools.screen.ScreenRecordingState]: a `StateFlow` lets the
 * stop tool await/inspect the transition instead of polling, and a background recording can be
 * stopped from a later, independent tool invocation.
 *
 * References:
 * - kotlinx.coroutines `StateFlow` — https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
 */
object CameraRecordingState {

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

    /**
     * The recorder/session pair of the running recording. Kept here (not only in the flow) so that
     * `camera_record_stop` — which runs in a different tool invocation — can actually stop it.
     * Mutated and closed only from the Camera2 worker thread (see [Camera2Controller]).
     */
    internal var active: ActiveRecording? = null

    /** Handle to a recording in progress; owned by [Camera2Controller]. */
    internal class ActiveRecording(
        val target: File,
        val withAudio: Boolean,
        val stop: () -> String,
    )

    internal fun markStarted(target: File, withAudio: Boolean) {
        _status.value = Status(
            active = true,
            path = target.absolutePath,
            startedAtEpochMs = System.currentTimeMillis(),
            withAudio = withAudio,
            error = null,
        )
    }

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

    internal fun reset() {
        active = null
        _status.value = Status()
    }
}
