package com.andharness.ru.data.tools.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide result slot for a single-frame screen capture performed by
 * [com.andharness.ru.bg.ScreenRecordService] (`ACTION_CAPTURE`).
 *
 * The tool (`screen_capture_region`) cannot call `ImageReader` itself: on Android 14+ (API 34+) the
 * `MediaProjection` token may only be obtained from a running foreground service of type
 * `mediaProjection`, so the frame is captured in the service and the honest outcome is published
 * here for the tool to await.
 *
 * A `StateFlow` is used so the tool can `first { }`-await completion instead of polling:
 * https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
 */
object ScreenCaptureState {

    /** Terminal state of the last capture attempt. [done] is false while a capture is in flight. */
    data class Status(
        val done: Boolean = false,
        val ok: Boolean = false,
        val path: String? = null,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Clears the slot before a new capture so a stale outcome is never reported as the current one. */
    internal fun reset() {
        _status.value = Status()
    }

    /** Publishes a verified success (the file exists and decodes). */
    internal fun success(path: String) {
        _status.value = Status(done = true, ok = true, path = path)
    }

    /** Publishes an honest failure reason; never a silent fallback. */
    internal fun failure(error: String) {
        _status.value = Status(done = true, ok = false, error = error)
    }
}
