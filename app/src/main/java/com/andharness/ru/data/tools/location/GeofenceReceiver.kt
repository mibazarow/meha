package com.andharness.ru.data.tools.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.andharness.ru.data.notifications.NotificationHelper
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Receives geofence transition broadcasts delivered by
 * [com.google.android.gms.location.GeofencingClient] through the `PendingIntent` registered by
 * `geofence_add`. It records every transition in [GeofenceStore] and posts a notification through
 * the app's [NotificationHelper] so the user sees the event.
 *
 * The receiver is manifest-declared and unexported; the OS delivers the broadcast explicitly to
 * this component.
 *
 * `GeofencingEvent.fromIntent(...)` returns null when the intent carries no geofencing data, and
 * `hasError()` reports a Play Services error code instead of a transition.
 * Reference: https://developer.android.com/develop/sensors-and-location/location/geofencing
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            Log.w(TAG, "no geofencing event in intent")
            return
        }
        if (event.hasError()) {
            Log.w(TAG, "geofencing error code=${event.errorCode}")
            return
        }

        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            else -> "UNKNOWN"
        }
        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        val location = event.triggeringLocation
        val timeMs = System.currentTimeMillis()

        val store = GeofenceStore(context)
        for (id in ids) {
            store.addEvent(
                GeofenceEvent(
                    geofenceId = id,
                    transition = transition,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    timeMs = timeMs,
                ),
            )
        }

        val who = if (ids.isEmpty()) "геозона" else "«${ids.joinToString(", ")}»"
        val what = when (transition) {
            "ENTER" -> "вход"
            "EXIT" -> "выход"
            "DWELL" -> "пребывание"
            else -> "событие"
        }
        val coords = if (location != null) {
            " (${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)})"
        } else {
            ""
        }
        try {
            NotificationHelper(context).postDoneNotification(
                text = "Геозона $who: $what$coords",
                notificationId = NotificationHelper.NOTIFICATION_ID_GEOFENCE,
                title = "Меха: геозона",
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to post geofence notification", e)
        }
        Log.i(TAG, "geofence transition=$transition ids=$ids")
    }

    private companion object {
        private const val TAG = "GeofenceReceiver"
    }
}
