package com.andharness.ru.data.tools.sensors

import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Process-wide holder of the currently active streaming sensor subscription.
 *
 * `sensor_subscribe` registers a `SensorEventListener` for a *bounded window only* and always
 * unregisters in `finally`. This holder exists so that a running window can also be interrupted
 * early from another tool call (`sensor_unsubscribe`) — for example when the agent starts a long
 * window and then decides it has enough samples. It also guarantees a single global subscription:
 * the listener registered here is the one, and only one, that `sensor_unsubscribe` can stop.
 *
 * All access is synchronized: the listener callbacks arrive on the main looper while the tools run
 * on a background dispatcher.
 *
 * Reference: https://developer.android.com/reference/android/hardware/SensorManager
 */
object SensorSubscriptionState {

    private val lock = Any()

    private var manager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var typeName: String? = null

    /** Registers the currently active listener (called right after `registerListener` succeeded). */
    fun setActive(manager: SensorManager, listener: SensorEventListener, type: String) {
        synchronized(lock) {
            this.manager = manager
            this.listener = listener
            this.typeName = type
        }
    }

    /** Clears the holder only when [listener] is the currently recorded one. */
    fun clearIf(listener: SensorEventListener) {
        synchronized(lock) {
            if (this.listener === listener) {
                this.manager = null
                this.listener = null
                this.typeName = null
            }
        }
    }

    /**
     * Unregisters the active subscription.
     *
     * @return the sensor name that was stopped, or null when there was no active subscription.
     */
    fun stop(): String? {
        val (mgr, lis, type) = synchronized(lock) {
            val snapshot = Triple(manager, listener, typeName)
            manager = null
            listener = null
            typeName = null
            snapshot
        }
        if (mgr != null && lis != null) {
            runCatching { mgr.unregisterListener(lis) }
        }
        return type
    }
}
