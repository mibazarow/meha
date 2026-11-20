package com.andharness.ru.data.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.andharness.ru.domain.automation.AutomationEvent
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.automation.RuleEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manifest receiver for the `boot` automation trigger (update.md §11.3): `BOOT_COMPLETED` is on the
 * Android 8+ exemption list, so it is delivered even after process death.
 *
 * On boot it (a) starts [AutomationTriggerManager] — which re-registers the runtime receivers and
 * re-arms the persisted `time` rules — and (b) runs every enabled `boot` rule.
 *
 * Reference: https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
 */
@AndroidEntryPoint
class AutomationBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var engine: RuleEngine

    @Inject
    lateinit var triggerManager: AutomationTriggerManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                triggerManager.start()
                engine.handle(AutomationEvent(triggerType = AutomationSpec.TRIGGER_BOOT))
            } catch (e: Exception) {
                Log.e(TAG, "Boot automation dispatch failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "Andharness"
    }
}
