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
import com.andharness.ru.data.tools.web.WebEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Foreground service that HOSTS the offscreen [WebEngine] (the `web_*` browsing session).
 *
 * Why: an offscreen [android.webkit.WebView] only survives while its process does. Hosting the engine
 * in a foreground service (`dataSync`) keeps the process — and therefore the live session (DOM,
 * history, scroll position, cookies) — alive while the app is backgrounded, without a visible window.
 *
 * Mandatory API order (Android 14+, API 34+): [ServiceCompat.startForeground] is called with an
 * explicit [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC] on the very first start command, BEFORE
 * the engine is created. The manifest declares `android:foregroundServiceType="dataSync"` plus the
 * `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` permissions (both already present for the
 * other services).
 *
 * The engine is created on this service's main thread (a `WebView` needs a thread with a `Looper`)
 * and published through the singleton companion holder that
 * `com.andharness.ru.data.tools.web.WebAutomationToolsHandler` reads. When the service cannot start
 * (the Android 12+ background-start
 * restriction throws `ForegroundServiceStartNotAllowedException`), the handler keeps using its own
 * in-process engine — the tools behave identically, only the hosting differs.
 *
 * References:
 * - Foreground services: https://developer.android.com/develop/background-work/services/fgs
 * - FGS types: https://developer.android.com/develop/background-work/services/fgs/service-types
 * - Android 14 FGS-type requirement: https://developer.android.com/about/versions/14/changes/fgs-types-required
 * - `ServiceCompat.startForeground`: https://developer.android.com/reference/androidx/core/app/ServiceCompat#startForeground(android.app.Service,int,android.app.Notification,int)
 */
@AndroidEntryPoint
class WebEngineService : Service() {

    @Inject
    lateinit var notifications: NotificationHelper

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(startId)
            ACTION_STOP -> {
                stopForegroundNow()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(startId: Int) {
        notifications.ensureChannels()
        val foreground = try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.FGS_ID_WEB,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(dataSync) failed", e)
            false
        }
        if (!foreground) {
            markStartFailed()
            stopSelf(startId)
            return
        }
        // `onStartCommand` runs on the main thread, which is exactly where a WebView must live.
        val engine = hostedEngine ?: WebEngine(applicationContext, okHttpClient).also { publish(it) }
        Log.i(TAG, "web engine hosted in foreground service (tools=${engine.handlerNames().size})")
    }

    override fun onDestroy() {
        // Runs on the main thread; the engine destroys its WebViews there.
        val engine = hostedEngine
        clearEngine()
        runCatching { engine?.destroyAll() }
        stopForegroundNow()
        super.onDestroy()
    }

    private fun stopForegroundNow() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    /** Low-importance, non-dismissible status notification — the service is silent by design. */
    private fun notification(): Notification =
        NotificationCompat.Builder(this, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Меха")
            .setContentText("Веб-сессия активна в фоне…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val TAG = "WebEngineService"

        /** How often [awaitEngine] re-checks for the published engine while waiting. */
        private const val POLL_MS = 50L

        const val ACTION_START = "com.andharness.ru.action.WEB_ENGINE_START"
        const val ACTION_STOP = "com.andharness.ru.action.WEB_ENGINE_STOP"

        /** The engine hosted by the running service, or null while no service instance exists. */
        @Volatile
        internal var hostedEngine: WebEngine? = null

        /** Latched when a start attempt was refused, so callers do not wait for a service that won't come. */
        @Volatile
        internal var startFailed = false

        /** The currently hosted engine, or null. */
        fun engine(): WebEngine? = hostedEngine

        internal fun publish(engine: WebEngine) {
            hostedEngine = engine
            startFailed = false
        }

        internal fun markStartFailed() {
            startFailed = true
        }

        internal fun clearEngine() {
            hostedEngine = null
        }

        /**
         * Asks the OS to start the hosting service. Returns false when the start is refused — on
         * Android 12+ (API 31+) a foreground service cannot be started from the background, which is
         * the honest fallback path for the caller.
         */
        fun start(context: Context): Boolean {
            if (hostedEngine != null) return true
            startFailed = false
            return try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context, WebEngineService::class.java).setAction(ACTION_START),
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
                startFailed = true
                false
            }
        }

        /**
         * Waits at most [timeoutMs] for the service to publish its engine. Returns null when the
         * start was refused or nothing was published in time, so the caller can fall back in-process.
         */
        suspend fun awaitEngine(timeoutMs: Long): WebEngine? {
            hostedEngine?.let { return it }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                hostedEngine?.let { return it }
                if (startFailed) return null
                delay(POLL_MS)
            }
            return hostedEngine
        }

        /**
         * Stops a running instance (the hosted engine is destroyed in `onDestroy`). Uses
         * [Context.stopService] rather than `startService(ACTION_STOP)`: the latter throws from the
         * background on API 26+ and could start a fresh instance that never promotes itself to the
         * foreground.
         * Reference: https://developer.android.com/develop/background-work/services#Stopping
         */
        fun stop(context: Context): Boolean = try {
            context.applicationContext.stopService(
                Intent(context, WebEngineService::class.java),
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            false
        }
    }
}
