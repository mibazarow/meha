package com.andharness.ru.data.crash

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.andharness.ru.data.core.WatchdogState
import com.andharness.ru.data.db.dao.MessageDao
import com.andharness.ru.data.notifications.NotificationChatRouter
import com.andharness.ru.data.notifications.NotificationChatTopics
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.crash.CrashLog
import com.andharness.ru.domain.crash.CrashLogRepository
import com.andharness.ru.domain.crash.CrashStackTruncator
import com.andharness.ru.domain.crash.RestartStateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * Crash protection (update.md §1.2). Installs a [Thread.setDefaultUncaughtExceptionHandler] that,
 * on a fatal crash:
 *
 * 1. records a bounded crash log (`crash_logs`, Room v14),
 * 2. snapshots the unfinished task (chat + last user message + pending flag),
 * 3. schedules a restart through [RestartScheduler] — unless [RestartPolicy] says the loop limit is
 *    reached, in which case it posts an honest "auto-restart stopped" notification instead,
 * 4. ALWAYS delegates to the previously installed handler afterwards, so the crash is never
 *    swallowed and the system still kills/reports the process normally.
 *
 * The recording work runs under [runBlocking] with a hard timeout because the process is about to
 * die — there is no live coroutine scope left to use, and the write must finish before delegation.
 *
 * Official references:
 * - https://developer.android.com/reference/java/lang/Thread#setDefaultUncaughtExceptionHandler(java.lang.Thread.UncaughtExceptionHandler)
 * - https://developer.android.com/reference/android/app/AlarmManager
 * - https://developer.android.com/topic/libraries/architecture/workmanager
 */
@Singleton
class CrashGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashLogs: CrashLogRepository,
    private val restartState: RestartStateRepository,
    private val scheduler: RestartScheduler,
    private val snapshots: CrashSnapshotStore,
    private val notifications: NotificationHelper,
    private val notificationChatRouter: NotificationChatRouter,
    private val currentChat: CurrentChatProvider,
    private val messageDao: MessageDao
) {

    private val installed = AtomicBoolean(false)
    private val startedActivities = AtomicInteger(0)

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** Installs the handler and the foreground tracker. Idempotent. */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        registerForegroundTracker()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handle(thread, throwable)
        }
        Log.i(TAG, "CrashGuard installed")
    }

    private fun registerForegroundTracker() {
        val application = context as? Application ?: return
        runCatching {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    startedActivities.incrementAndGet()
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    startedActivities.updateAndGet { current -> if (current > 0) current - 1 else 0 }
                }

                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
                override fun onActivityResumed(activity: android.app.Activity) = Unit
                override fun onActivityPaused(activity: android.app.Activity) = Unit
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            })
        }.onFailure { Log.e(TAG, "Registering foreground tracker failed", it) }
    }

    private fun handle(thread: Thread, throwable: Throwable) {
        try {
            record(throwable)
        } catch (t: Throwable) {
            Log.e(TAG, "CrashGuard failed to record the crash", t)
        } finally {
            // NEVER swallow the crash: hand it back to the handler that was installed before us.
            val previous = previousHandler
            if (previous != null && previous !== Thread.getDefaultUncaughtExceptionHandler()) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(EXIT_CODE)
            }
        }
    }

    private fun record(throwable: Throwable) {
        val now = System.currentTimeMillis()
        val wasForeground = startedActivities.get() > 0
        runBlocking {
            withTimeoutOrNull(RECORD_TIMEOUT_MS) {
                val decision = RestartPolicy.onCrash(restartState.get(), now)
                restartState.save(decision.state)

                // Crash unwind has no live coroutine context: use the best-effort process-global
                // snapshot (never used for tool dispatch — see CurrentChatProvider).
                val chatId = currentChat.lastActiveChatIdForCrash()
                val lastUserMessage = chatId?.let { id ->
                    runCatching { messageDao.getLastUserMessage(id) }.getOrNull()
                }
                snapshots.save(chatId, lastUserMessage, now)

                crashLogs.record(
                    CrashLog(
                        at = now,
                        exceptionType = throwable.javaClass.name,
                        stack = CrashStackTruncator.truncate(throwable.stackTraceToString()),
                        appVersion = appVersion(),
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        apiLevel = Build.VERSION.SDK_INT,
                        wasForeground = wasForeground,
                        restartNumber = decision.restartNumber
                    )
                )
                crashLogs.prune(now)

                if (decision.allowed) {
                    val mode = scheduler.schedule(decision.delayMs)
                    Log.i(TAG, "restart #${decision.restartNumber} scheduled in ${decision.delayMs} ms ($mode)")
                } else {
                    // Loop protection: stop restarting and say so honestly.
                    WatchdogState.markStopped(WatchdogState.REASON_CRASHED_LIMIT)
                    scheduler.cancel()
                    val text = "Меха падает повторно, авто-перезапуск остановлен. " +
                        "Диагностика: инструмент self_diagnostics."
                    val crashChatId = notificationChatRouter.routeIfNeeded(
                        topic = NotificationChatTopics.CRASH,
                        title = NotificationChatTopics.CRASH_TITLE,
                        text = text,
                        chatId = null,
                    )
                    notifications.postDoneNotification(
                        text = text,
                        chatId = crashChatId,
                        notificationId = NOTIFICATION_ID_RESTART_STOPPED,
                        title = "Меха: авто-перезапуск остановлен"
                    )
                    Log.w(TAG, "restart limit reached; auto-restart stopped")
                }
            } ?: Log.w(TAG, "crash recording timed out after ${RECORD_TIMEOUT_MS} ms")
        }
    }

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    companion object {
        private const val TAG = "Andharness"
        private const val NOTIFICATION_ID_RESTART_STOPPED = 1_008
        private const val RECORD_TIMEOUT_MS = 2_500L
        private const val EXIT_CODE = 10
    }
}
