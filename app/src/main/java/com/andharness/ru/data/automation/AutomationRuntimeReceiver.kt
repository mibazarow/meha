package com.andharness.ru.data.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andharness.ru.domain.automation.AutomationSpec

/**
 * Runtime-only receiver for power/screen triggers (update.md §11.3): `ACTION_POWER_CONNECTED`,
 * `ACTION_BATTERY_LOW`, `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF`.
 *
 * These broadcasts are NOT manifest-deliverable on Android 8+ (they are not on the exemption list),
 * so the receiver is registered dynamically by [AutomationTriggerManager] and therefore only fires
 * while the app process is alive. That limitation is reported honestly by the tools.
 *
 * Reference: https://developer.android.com/develop/background-work/background-tasks/broadcasts
 */
class AutomationRuntimeReceiver(
    private val onTrigger: (String) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val trigger = triggerFor(intent.action) ?: return
        onTrigger(trigger)
    }

    private fun triggerFor(action: String?): String? = when (action) {
        Intent.ACTION_POWER_CONNECTED -> AutomationSpec.TRIGGER_CHARGING
        Intent.ACTION_BATTERY_LOW -> AutomationSpec.TRIGGER_BATTERY_LOW
        Intent.ACTION_SCREEN_ON -> AutomationSpec.TRIGGER_SCREEN_ON
        Intent.ACTION_SCREEN_OFF -> AutomationSpec.TRIGGER_SCREEN_OFF
        else -> null
    }
}
