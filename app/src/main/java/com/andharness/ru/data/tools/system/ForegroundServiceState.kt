package com.andharness.ru.data.tools.system

import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide (singleton) state of the generic foreground service created by `fgs_start` /
 * `keep_alive_start`, plus the partial wake lock that `keep_alive_start` holds.
 *
 * The foreground services ([com.andharness.ru.bg.GenericForegroundService] and
 * [com.andharness.ru.bg.ShortForegroundService]) publish their real state here, and the tools read it
 * back so `fgs_stop` / `keep_alive_stop` can report what actually happened instead of assuming the
 * stop request succeeded.
 *
 * A `StateFlow` lets a tool `first { … }`-await a transition with a bounded timeout instead of
 * polling. Reference:
 * https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
 */
object ForegroundServiceState {

    /** Current status of the generic foreground service / keep-alive pair. */
    data class Status(
        val runningType: String? = null,
        val startedAtMs: Long = 0L,
        val keepAlive: Boolean = false,
        val wakeLockHeld: Boolean = false,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    /** Called by the service once `startForeground` succeeded. */
    fun markStarted(type: String) {
        _status.value = _status.value.copy(
            runningType = type,
            startedAtMs = System.currentTimeMillis(),
            error = null,
        )
    }

    /** Called by the service when it stops (or when the stop request was rejected). */
    fun markStopped(error: String? = null) {
        if (error != null) {
            _status.value = _status.value.copy(runningType = null, startedAtMs = 0L, error = error)
        } else {
            _status.value = _status.value.copy(runningType = null, startedAtMs = 0L)
        }
    }

    fun markError(message: String) {
        _status.value = _status.value.copy(runningType = null, startedAtMs = 0L, error = message)
    }

    fun setKeepAlive(enabled: Boolean) {
        _status.value = _status.value.copy(keepAlive = enabled)
    }

    /**
     * Stores the acquired wake lock so only one is ever held by this app and `keep_alive_stop` can
     * release exactly the lock that was acquired.
     */
    fun holdWakeLock(lock: PowerManager.WakeLock) {
        synchronized(this.lock) { wakeLock = lock }
        _status.value = _status.value.copy(wakeLockHeld = true)
    }

    /**
     * Releases the held wake lock.
     *
     * @return true when a lock was actually held and has been released, false when nothing was held.
     */
    fun releaseWakeLock(): Boolean {
        val held = synchronized(lock) {
            val current = wakeLock
            wakeLock = null
            current
        }
        val wasHeld = held?.isHeld == true
        if (wasHeld) {
            runCatching { held?.release() }
        }
        _status.value = _status.value.copy(wakeLockHeld = false)
        return wasHeld
    }
}
