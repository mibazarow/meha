package com.andharness.ru.bg

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.andharness.ru.R
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.tools.location.LocationUpdatesState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service (type `location`) that subscribes to continuous location updates with
 * [LocationManager.requestLocationUpdates] and pushes every received fix into the singleton
 * [LocationUpdatesState] until stopped.
 *
 * API ORDER:
 * 1. Verify `ACCESS_FINE_LOCATION` is granted — otherwise the service reports an honest error and
 *    stops (never a silent no-op).
 * 2. Call [ServiceCompat.startForeground] with
 *    [ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION] BEFORE subscribing (Android 14+/API 34+).
 * 3. Pick an enabled provider (GPS for high accuracy, network otherwise, with a fallback) and call
 *    `requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, looper)`.
 * 4. On stop: `removeUpdates(listener)`, mark the state stopped, drop the foreground notification.
 *
 * Background use additionally needs `ACCESS_BACKGROUND_LOCATION`; without it the OS still delivers
 * updates only while the app is effectively in the foreground. The tool layer reports that honestly.
 *
 * References:
 * - Permissions: https://developer.android.com/develop/sensors-and-location/location/permissions
 * - `LocationManager.requestLocationUpdates(...)`:
 *   https://developer.android.com/reference/android/location/LocationManager#requestLocationUpdates(java.lang.String,%20long,%20float,%20android.location.LocationListener,%20android.os.Looper)
 * - Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
 * - Android 14 FGS-type requirement: https://developer.android.com/about/versions/14/changes/fgs-types-required#location
 */
@AndroidEntryPoint
class LocationUpdatesService : Service() {

    @Inject
    lateinit var notifications: NotificationHelper

    private var locationManager: LocationManager? = null
    private var listener: LocationListener? = null
    private var intervalSec = DEFAULT_INTERVAL_SEC
    private var minDistanceM = DEFAULT_MIN_DISTANCE_M
    private var highAccuracy = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent, startId)
            ACTION_STOP -> {
                stopUpdates()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent, startId: Int) {
        if (listener != null) {
            Log.w(TAG, "start ignored: stream already running")
            LocationUpdatesState.markStarted(intervalSec, minDistanceM, highAccuracy)
            return
        }

        intervalSec = intent.getIntExtra(EXTRA_INTERVAL_SEC, DEFAULT_INTERVAL_SEC)
            .coerceAtLeast(MIN_INTERVAL_SEC)
        minDistanceM = intent.getFloatExtra(EXTRA_MIN_DISTANCE_M, DEFAULT_MIN_DISTANCE_M)
            .coerceAtLeast(0f)
        highAccuracy = intent.getBooleanExtra(EXTRA_HIGH_ACCURACY, false)

        notifications.ensureChannels()
        val foreground = try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.FGS_ID_LOCATION,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
        if (!foreground) {
            LocationUpdatesState.markStopped("Не удалось перевести службу локации в foreground.")
            stopSelf(startId)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fail("Нет разрешения ACCESS_FINE_LOCATION — поток обновлений локации невозможен.", startId)
            return
        }

        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            fail("Служба геолокации недоступна на этом устройстве.", startId)
            return
        }
        locationManager = manager

        val provider = chooseProvider(manager, highAccuracy)
        if (provider == null) {
            fail("Нет включённых провайдеров геолокации (GPS и сеть выключены).", startId)
            return
        }

        val newListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                LocationUpdatesState.addFix(
                    LocationUpdatesState.Fix(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy,
                        provider = location.provider,
                        timeMs = location.time,
                    ),
                )
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            manager.requestLocationUpdates(
                provider,
                intervalSec * 1000L,
                minDistanceM,
                newListener,
                Looper.getMainLooper(),
            )
        } catch (e: SecurityException) {
            fail("Нет разрешения на геолокацию (нужен ACCESS_FINE_LOCATION).", startId)
            return
        } catch (e: Exception) {
            fail("Не удалось подписаться на обновления локации: ${e.message}", startId)
            return
        }

        listener = newListener
        LocationUpdatesState.markStarted(intervalSec, minDistanceM, highAccuracy)
        Log.i(TAG, "location stream started (provider=$provider, interval=${intervalSec}s, " +
            "minDistance=${minDistanceM}m, highAccuracy=$highAccuracy)")
    }

    /**
     * Picks the provider to subscribe to: GPS first for high accuracy, network first otherwise, and
     * falls back to any other enabled non-passive provider when the preferred one is disabled.
     * `isProviderEnabled` returns false when the user turned the provider off.
     * https://developer.android.com/reference/android/location/LocationManager#isProviderEnabled(java.lang.String)
     */
    private fun chooseProvider(manager: LocationManager, highAccuracy: Boolean): String? {
        val enabled = try {
            manager.getProviders(true)
        } catch (_: Exception) {
            emptyList()
        }
        if (enabled.isEmpty()) return null
        val preferred = if (highAccuracy) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        return preferred.firstOrNull { enabled.contains(it) }
            ?: enabled.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
    }

    private fun fail(message: String, startId: Int) {
        Log.e(TAG, message)
        LocationUpdatesState.markStopped(message)
        stopForegroundNow()
        stopSelf(startId)
    }

    /** Removes the provider subscription exactly once and updates the shared state. */
    private fun stopUpdates() {
        if (stopping) return
        stopping = true
        val manager = locationManager
        val active = listener
        listener = null
        locationManager = null
        if (manager != null && active != null) {
            try {
                manager.removeUpdates(active)
            } catch (_: Exception) {
            }
        }
        LocationUpdatesState.markStopped()
        stopForegroundNow()
        Log.i(TAG, "location stream stopped")
    }

    private fun stopForegroundNow() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        stopUpdates()
        super.onDestroy()
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Меха")
            .setContentText(
                "Отслеживаю местоположение" +
                    if (highAccuracy) " (высокая точность)…" else "…",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val TAG = "LocationUpdatesService"

        const val ACTION_START = "com.andharness.ru.action.LOCATION_UPDATES_START"
        const val ACTION_STOP = "com.andharness.ru.action.LOCATION_UPDATES_STOP"
        const val EXTRA_INTERVAL_SEC = "location_updates_interval_sec"
        const val EXTRA_MIN_DISTANCE_M = "location_updates_min_distance_m"
        const val EXTRA_HIGH_ACCURACY = "location_updates_high_accuracy"

        const val DEFAULT_INTERVAL_SEC = 30
        const val MIN_INTERVAL_SEC = 5
        const val DEFAULT_MIN_DISTANCE_M = 10f

        /** Starts the location foreground service; returns false when the OS refuses to start it. */
        fun start(
            context: Context,
            intervalSec: Int,
            minDistanceM: Float,
            highAccuracy: Boolean,
        ): Boolean {
            val intent = Intent(context, LocationUpdatesService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INTERVAL_SEC, intervalSec)
                .putExtra(EXTRA_MIN_DISTANCE_M, minDistanceM)
                .putExtra(EXTRA_HIGH_ACCURACY, highAccuracy)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
                false
            }
        }

        /**
         * Stops a running service; the provider subscription is removed in `onDestroy`. Uses
         * [Context.stopService] rather than `startService(ACTION_STOP)`, which throws from the
         * background on API 26+ and could start a fresh instance.
         * Reference: https://developer.android.com/develop/background-work/services#Stopping
         */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LocationUpdatesService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "stop request failed", e)
            }
        }
    }
}
