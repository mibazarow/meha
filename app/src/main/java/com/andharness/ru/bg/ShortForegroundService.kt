package com.andharness.ru.bg

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * Foreground service of type `shortService` (`fgs_start type=shortService`).
 *
 * Why a separate service: the platform IGNORES a `shortService` declaration that is combined with
 * any other foreground-service type, so it cannot live on [GenericForegroundService] next to
 * `dataSync`/`specialUse`. `shortService` also requires no type-specific permission (only the base
 * `FOREGROUND_SERVICE`), but it is strictly time-limited — the platform expects the service to stop
 * within ~3 minutes and otherwise raises `Service.onTimeout` / an ANR.
 *
 * Therefore this service stops ITSELF after [MAX_LIFETIME_MS] (2.5 minutes), comfortably below the
 * platform limit, instead of relying on a timeout callback that does not exist before API 34.
 *
 * Reference: https://developer.android.com/develop/background-work/services/fgs/service-types
 */
@AndroidEntryPoint
class ShortForegroundService : Service() {

    @Inject
    lateinit var notifications: NotificationHelper

    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable {
        Log.i(TAG, "shortService lifetime budget reached — stopping")
        stopForegroundNow()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(startId)
            ACTION_STOP -> {
                handler.removeCallbacks(autoStop)
                stopForegroundNow()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(startId: Int) {
        notifications.ensureChannels()
        val started = try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.FGS_ID_SHORT,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(shortService) failed", e)
            ForegroundServiceState.markError(
                "Не удалось перевести shortService в foreground: ${e.message ?: e.javaClass.simpleName}"
            )
            false
        }
        if (!started) {
            stopSelf(startId)
            return
        }
        ForegroundServiceState.markStarted(TYPE_SHORT_SERVICE)
        handler.postDelayed(autoStop, MAX_LIFETIME_MS)
        Log.i(TAG, "shortService started (auto-stop in ${MAX_LIFETIME_MS}ms)")
    }

    private fun stopForegroundNow() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        stopForegroundNow()
        ForegroundServiceState.markStopped()
        super.onDestroy()
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Меха")
            .setContentText("Короткая задача (до 2,5 минут)…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val TAG = "ShortForegroundService"

        const val ACTION_START = "com.andharness.ru.action.SHORT_FGS_START"
        const val ACTION_STOP = "com.andharness.ru.action.SHORT_FGS_STOP"

        const val TYPE_SHORT_SERVICE = "shortService"

        /** Self-imposed lifetime, below the platform's ~3-minute shortService budget. */
        const val MAX_LIFETIME_MS = 150_000L

        fun start(context: Context): Boolean {
            val intent = Intent(context, ShortForegroundService::class.java).setAction(ACTION_START)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService(shortService) failed", e)
                ForegroundServiceState.markError(
                    "Система запретила запуск shortService: ${e.message ?: e.javaClass.simpleName}"
                )
                false
            }
        }

        /**
         * Stops a running instance. [Context.stopService] (not `startService(ACTION_STOP)`) so the
         * call is safe from the background on API 26+ and can never spin up a fresh instance; the
         * pending auto-stop callback is cancelled and the foreground notification removed in
         * `onDestroy`.
         * Reference: https://developer.android.com/develop/background-work/services#Stopping
         */
        fun stop(context: Context): Boolean = try {
            context.stopService(Intent(context, ShortForegroundService::class.java))
            true
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            false
        }
    }
}
