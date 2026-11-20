package com.andharness.ru.bg

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.andharness.ru.R
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.tools.system.ForegroundServiceState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Minimal, manifest-declared foreground service used by `fgs_start` and `keep_alive_start`.
 *
 * It does no work of its own — it exists purely to hold the process in the foreground with an
 * EXPLICIT type requested by the caller (`dataSync` or `specialUse`). Mandatory API order:
 * [ServiceCompat.startForeground] is called on the very first start command, with the matching
 * `ServiceInfo.FOREGROUND_SERVICE_TYPE_*`.
 *
 * Manifest requirements (Android 14+/API 34+):
 * - `android:foregroundServiceType="dataSync|specialUse"` on this service;
 * - the `FOREGROUND_SERVICE` permission plus the per-type permissions
 *   `FOREGROUND_SERVICE_DATA_SYNC` / `FOREGROUND_SERVICE_SPECIAL_USE`;
 * - a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` explaining the
 *   specialUse subtype.
 *
 * `shortService` is deliberately NOT declared here: the system ignores a `shortService` declaration
 * combined with any other type, so it lives in [ShortForegroundService] alone.
 *
 * Background-start restriction: on Android 12+ (API 31+) starting a foreground service while the app
 * is in the background is not allowed and throws `ForegroundServiceStartNotAllowedException`. The
 * callers catch it and report honestly.
 *
 * References:
 * - Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
 * - Android 14 FGS-type requirement: https://developer.android.com/about/versions/14/changes/fgs-types-required
 */
@AndroidEntryPoint
class GenericForegroundService : Service() {

    @Inject
    lateinit var notifications: NotificationHelper

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent, startId)
            ACTION_STOP -> {
                stopForegroundNow()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent, startId: Int) {
        val typeName = normalizeType(intent.getStringExtra(EXTRA_TYPE))
        if (typeName == null) {
            ForegroundServiceState.markError("Неизвестный тип foreground-сервиса.")
            stopSelf(startId)
            return
        }
        notifications.ensureChannels()
        val runtimeType = when (typeName) {
            TYPE_DATA_SYNC -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        val started = try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.FGS_ID_GENERIC,
                notification(typeName),
                runtimeType
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground($typeName) failed", e)
            ForegroundServiceState.markError(
                "Не удалось перевести сервис в foreground ($typeName): ${e.message ?: e.javaClass.simpleName}"
            )
            false
        }
        if (!started) {
            stopSelf(startId)
            return
        }
        ForegroundServiceState.markStarted(typeName)
        Log.i(TAG, "foreground service started (type=$typeName)")
    }

    private fun stopForegroundNow() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        stopForegroundNow()
        ForegroundServiceState.markStopped()
        super.onDestroy()
    }

    private fun notification(typeName: String): Notification =
        NotificationCompat.Builder(this, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Меха")
            .setContentText("Удерживаю процесс в foreground ($typeName)…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val TAG = "GenericForegroundService"

        const val ACTION_START = "com.andharness.ru.action.GENERIC_FGS_START"
        const val ACTION_STOP = "com.andharness.ru.action.GENERIC_FGS_STOP"
        const val EXTRA_TYPE = "generic_fgs_type"

        const val TYPE_DATA_SYNC = "dataSync"
        const val TYPE_SPECIAL_USE = "specialUse"

        /** Canonical type names accepted from the tool layer. */
        val SUPPORTED_TYPES = listOf(TYPE_DATA_SYNC, TYPE_SPECIAL_USE)

        /** Case/underscore-tolerant normalisation of a requested type, or null when unsupported. */
        fun normalizeType(raw: String?): String? {
            val key = raw?.trim()?.lowercase()?.replace("_", "")?.replace("-", "") ?: return null
            return when (key) {
                "datasync" -> TYPE_DATA_SYNC
                "specialuse" -> TYPE_SPECIAL_USE
                else -> null
            }
        }

        /** Starts this service; returns false when the OS refuses to start it. */
        fun start(context: Context, type: String): Boolean {
            val intent = Intent(context, GenericForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TYPE, type)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService($type) failed", e)
                ForegroundServiceState.markError(
                    "Система запретила запуск foreground-сервиса: ${e.message ?: e.javaClass.simpleName}"
                )
                false
            }
        }

        /**
         * Stops a running instance. [Context.stopService] (not `startService(ACTION_STOP)`) so the
         * call is safe from the background on API 26+ and can never start a fresh instance that
         * never reaches `startForeground`; `onDestroy` removes the notification and updates
         * [ForegroundServiceState].
         * Reference: https://developer.android.com/develop/background-work/services#Stopping
         */
        fun stop(context: Context): Boolean = try {
            context.stopService(Intent(context, GenericForegroundService::class.java))
            true
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            false
        }
    }
}
