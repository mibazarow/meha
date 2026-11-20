package com.andharness.ru.data.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.andharness.ru.bg.WatchdogService
import com.andharness.ru.data.schedule.ScheduleCatchUp
import com.andharness.ru.domain.automation.AutomationRuleRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restarts the background core after a reboot or a self-update (update.md §1.3), but ONLY when at
 * least one subsystem is enabled — the same honest rule the service itself enforces. When nothing is
 * enabled it does not start anything and records [WatchdogState.REASON_NO_FEATURES].
 *
 * `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are on the Android 8+ manifest-exemption list, so they
 * are delivered even after process death.
 *
 * It also runs the deterministic boot-time scheduled-task catch-up ([ScheduleCatchUp.reconcile]): a
 * run whose due time passed while the process was dead is fired here, instead of relying on the
 * process happening to start for another reason. Bounded, idempotent and fire-and-forget.
 *
 * Reference: https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
 */
@AndroidEntryPoint
class WatchdogBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var config: WatchdogConfigStore

    @Inject
    lateinit var automationRules: AutomationRuleRepository

    /** Fires the missed scheduled runs on boot/self-update; see [ScheduleCatchUp]. */
    @Inject
    lateinit var scheduleCatchUp: ScheduleCatchUp

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val reason = if (action == Intent.ACTION_BOOT_COMPLETED) {
            WatchdogService.REASON_BOOT
        } else {
            WatchdogService.REASON_PACKAGE_REPLACED
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Deterministic boot-time catch-up: fire the scheduled runs missed while the process
                // was dead. Bounded/idempotent and best-effort; must never block or break the boot.
                runCatching { scheduleCatchUp.reconcile() }
                    .onFailure { Log.w(TAG, "Boot scheduled-task catch-up failed: ${it.message}") }
                if (anySubsystemEnabled()) {
                    WatchdogService.start(context, reason)
                } else {
                    WatchdogState.markStopped(WatchdogState.REASON_NO_FEATURES)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog boot start failed", e)
                WatchdogState.markStopped(WatchdogState.REASON_START_FAILED)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun anySubsystemEnabled(): Boolean {
        if (config.isCoreEnabled() || config.isInterceptionEnabled()) return true
        return try {
            automationRules.getAll().any { it.enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Checking automation rules failed; assuming disabled", e)
            false
        }
    }

    companion object {
        private const val TAG = "Andharness"
    }
}
