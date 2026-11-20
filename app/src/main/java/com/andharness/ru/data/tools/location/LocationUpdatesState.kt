package com.andharness.ru.data.tools.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide (singleton) holder of the background location update stream, shared between the
 * foreground service that actually subscribes to the provider
 * ([com.andharness.ru.bg.LocationUpdatesService]) and the tools that drive it
 * (`location_updates_start` / `location_updates_stop` / `location_updates_get`).
 *
 * The history is intentionally bounded (the newest [MAX_HISTORY] fixes) so a long-running stream
 * cannot grow memory without limit.
 *
 * A `StateFlow` is used so a tool can `first { }`-await a start/stop transition instead of polling.
 * Reference:
 * https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
 */
object LocationUpdatesState {

    /** One received location fix. */
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val provider: String?,
        val timeMs: Long,
    )

    /** Current stream status: whether it is [running] and with which request parameters. */
    data class Status(
        val running: Boolean = false,
        val intervalSec: Int = 0,
        val minDistanceM: Float = 0f,
        val highAccuracy: Boolean = false,
        val error: String? = null,
    )

    /** Hard cap on the retained history (newest wins). */
    const val MAX_HISTORY = 200

    private val _fixes = MutableStateFlow<List<Fix>>(emptyList())
    val fixes: StateFlow<List<Fix>> = _fixes.asStateFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Called by the service once `requestLocationUpdates` has been accepted by the provider. */
    internal fun markStarted(intervalSec: Int, minDistanceM: Float, highAccuracy: Boolean) {
        _status.value = Status(
            running = true,
            intervalSec = intervalSec,
            minDistanceM = minDistanceM,
            highAccuracy = highAccuracy,
            error = null,
        )
    }

    /** Called by the service when the stream stops (or fails to start); [error] is an honest reason. */
    internal fun markStopped(error: String? = null) {
        val previous = _status.value
        _status.value = Status(
            running = false,
            intervalSec = previous.intervalSec,
            minDistanceM = previous.minDistanceM,
            highAccuracy = previous.highAccuracy,
            error = error,
        )
    }

    /** Appends a fix, keeping only the newest [MAX_HISTORY] entries. */
    internal fun addFix(fix: Fix) {
        _fixes.value = (_fixes.value + fix).takeLast(MAX_HISTORY)
    }

    /**
     * Clears a previous session before a new one: the status (so a stale error is never reported as
     * the current one) and the history (so stale fixes are never reported as fresh).
     */
    internal fun reset() {
        _status.value = Status()
        _fixes.value = emptyList()
    }

    /** Newest-first snapshot of at most [limit] fixes (clamped to 1..[MAX_HISTORY]). */
    fun recent(limit: Int): List<Fix> =
        _fixes.value.takeLast(limit.coerceIn(1, MAX_HISTORY)).reversed()
}
