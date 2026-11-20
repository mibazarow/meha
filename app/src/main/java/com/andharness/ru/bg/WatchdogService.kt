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
import com.andharness.ru.data.automation.AutomationTriggerManager
import com.andharness.ru.data.core.WatchdogConfigStore
import com.andharness.ru.data.core.WatchdogState
import com.andharness.ru.data.crash.CrashSnapshotStore
import com.andharness.ru.data.crash.RestartScheduler
import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.data.notifications.NotificationChatRouter
import com.andharness.ru.data.notifications.NotificationChatTopics
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.automation.AutomationRuleRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The persistent background "core" (update.md §1.3): a foreground service of type `specialUse` that
 * hosts the automation trigger manager / rule engine for as long as the process is alive, and is the
 * target of crash restarts ([com.andharness.ru.data.crash.RestartReceiver]) and boot (re)starts
 * ([com.andharness.ru.data.core.WatchdogBootReceiver]).
 *
 * Honest auto-start rule: it starts ONLY when at least one core subsystem is enabled — the explicit
 * core toggle, the persisted interception flag, or at least one enabled automation rule. Otherwise it
 * does not start at all and reports [WatchdogState.REASON_NO_FEATURES].
 *
 * Manifest requirements (Android 14+/API 34+):
 * - `android:foregroundServiceType="specialUse"`;
 * - `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`;
 * - `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>`.
 *
 * Official references:
 * - Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
 * - `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`: https://developer.android.com/reference/android/content/pm/ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE
 * - `Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE`: https://developer.android.com/reference/android/Manifest.permission#FOREGROUND_SERVICE_SPECIAL_USE
 * - Background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
 * - Doze / App Standby (background windows are not guaranteed): https://developer.android.com/training/monitoring-device-state/doze-standby
 */
@AndroidEntryPoint
class WatchdogService : Service() {

    @Inject
    lateinit var triggerManager: AutomationTriggerManager

    @Inject
    lateinit var automationRules: AutomationRuleRepository

    @Inject
    lateinit var config: WatchdogConfigStore

    @Inject
    lateinit var notifications: NotificationHelper

    @Inject
    lateinit var notificationChatRouter: NotificationChatRouter

    @Inject
    lateinit var restartScheduler: RestartScheduler

    @Inject
    lateinit var snapshots: CrashSnapshotStore

    @Inject
    lateinit var powerGuard: PowerGuard

    /**
     * Owns the post-foreground subsystem resolution. Cancelled in [onDestroy] so a pending
     * coroutine cannot outlive (and resurrect work for) a stopped service.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundNow()
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: REASON_SYSTEM_RESTART
        return startCore(reason, startId)
    }

    private fun startCore(reason: String, startId: Int): Int {
        notifications.ensureChannels()
        // startForeground MUST be the first thing after a startForegroundService() call: the
        // platform gives us a hard deadline (Service.startForeground() /
        // ForegroundServiceDidNotStartInTimeException) and a blocking Room read on the main thread
        // here would risk missing it. So the subsystem check happens AFTER the foreground state is
        // established, inside a coroutine — never with runBlocking on the main thread.
        // Reference: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
        val started = try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.FGS_ID_CORE,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            true
        } catch (e: Exception) {
            // Includes ForegroundServiceStartNotAllowedException (API 31+) and the Android 14+
            // requirement that the declared type matches the runtime type.
            Log.e(TAG, "startForeground(specialUse) failed", e)
            false
        }
        if (!started) {
            WatchdogState.markStopped(WatchdogState.REASON_START_FAILED)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            if (!anySubsystemEnabled()) {
                // Nothing to host: stop honestly instead of idling in the foreground forever.
                WatchdogState.markStopped(WatchdogState.REASON_NO_FEATURES)
                stopForegroundNow()
                stopSelf(startId)
                return@launch
            }
            // A restart already happened — drop any stale restart request so we cannot double-restart.
            restartScheduler.cancel()
            // Power guard (update.md idea #19): the core itself keeps running (it is a cheap
            // foreground service), but the decision is stated honestly so the non-essential periodic
            // workers skips are explainable from the log.
            powerGuard.deferReason()?.let { reason ->
                Log.i(TAG, "power guard active: $reason — non-essential periodic work will be deferred")
            }
            // Hosts (for now) the automation engine: re-register runtime triggers and re-arm time
            // rules.
            runCatching { triggerManager.start() }
                .onFailure { Log.e(TAG, "Automation trigger manager start failed", it) }
            WatchdogState.markRunning(reason)
            offerUnfinishedTask()
            Log.i(TAG, "core started (reason=$reason)")
        }
        // START_STICKY: if the system kills us, it will re-create the service (with a null intent).
        return START_STICKY
    }

    /**
     * Reads the crash-time unfinished-task snapshot and, when one is pending, posts a notification
     * offering to continue it in the originating chat (update.md §1.4). The snapshot is NOT cleared
     * here: it stays until the user finishes or declines the task.
     */
    private suspend fun offerUnfinishedTask() {
        val snapshot = runCatching { snapshots.get() }.getOrNull() ?: return
        if (!snapshot.pending) return
        val text = "После сбоя осталась незавершённая задача. Откройте чат, чтобы продолжить."
        // A snapshot without an originating chat would otherwise land on the home screen; route it
        // into a chat so the offer always opens somewhere the user can continue.
        val chatId = notificationChatRouter.routeIfNeeded(
            topic = NotificationChatTopics.forBaseId(NotificationHelper.NOTIFICATION_ID_UNFINISHED),
            title = NotificationChatTopics.GENERAL_TITLE,
            text = text,
            chatId = snapshot.chatId,
        ) ?: snapshot.chatId
        notifications.postDoneNotification(
            text = text,
            chatId = chatId,
            notificationId = NotificationHelper.NOTIFICATION_ID_UNFINISHED,
            title = "Меха: незавершённая задача"
        )
    }

    /**
     * True when at least one core subsystem is enabled (update.md §1.3). Suspending: it is only
     * ever called from [scope], never with `runBlocking` on the main thread.
     */
    private suspend fun anySubsystemEnabled(): Boolean {
        if (config.isCoreEnabled() || config.isInterceptionEnabled()) return true
        return try {
            automationRules.getAll().any { it.enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Checking automation rules failed; assuming disabled", e)
            false
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Меха работает в фоне")
            .setContentText("Ядро активно: автоматизация и перехват событий.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun stopForegroundNow() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        scope.cancel()
        stopForegroundNow()
        if (WatchdogState.status.value.running) {
            WatchdogState.markStopped(WatchdogState.REASON_STOPPED)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Andharness"

        const val ACTION_START = "com.andharness.ru.action.WATCHDOG_START"
        const val ACTION_STOP = "com.andharness.ru.action.WATCHDOG_STOP"
        const val EXTRA_REASON = "watchdog_reason"

        const val REASON_EXPLICIT = "explicit"
        const val REASON_BOOT = "boot"
        const val REASON_PACKAGE_REPLACED = "package_replaced"
        const val REASON_SYSTEM_RESTART = "system_restart"
        const val REASON_CRASH_RESTART = "crash_restart"

        /** Starts (or pings) the core; returns false when the OS refuses to start the service. */
        fun start(context: Context, reason: String): Boolean {
            val intent = Intent(context, WatchdogService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REASON, reason)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                // Background-start restriction (API 31+): report honestly instead of pretending.
                Log.e(TAG, "startForegroundService(watchdog) failed", e)
                WatchdogState.markStopped(WatchdogState.REASON_START_FAILED)
                false
            }
        }

        /**
         * Stops the core and its foreground notification. Uses [Context.stopService] rather than a
         * `startService(ACTION_STOP)`: from the background on API 26+ the latter throws
         * (`IllegalStateException`) and, worse, can start a fresh instance that never promotes
         * itself to the foreground. `onDestroy` performs the same cleanup.
         * Reference: https://developer.android.com/develop/background-work/services#Stopping
         */
        fun stop(context: Context): Boolean = try {
            context.stopService(Intent(context, WatchdogService::class.java))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Stopping watchdog failed", e)
            false
        }
    }
}
