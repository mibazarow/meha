package com.andharness.ru.data.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.andharness.ru.domain.diagnostics.PowerAction
import com.andharness.ru.domain.diagnostics.PowerGuardPolicy
import com.andharness.ru.domain.diagnostics.PowerSnapshot
import com.andharness.ru.domain.diagnostics.PowerStateProvider
import com.andharness.ru.domain.diagnostics.PowerVerdict
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-facing power guard (update.md idea #19): reads the real power state and hands it to the
 * pure [PowerGuardPolicy] for the decision.
 *
 * Everything is read through official APIs and every read is failure-tolerant: an unavailable
 * signal is reported as unknown, never guessed.
 *
 * Official references:
 * - Battery level/charging: https://developer.android.com/reference/android/os/BatteryManager
 * - Battery saver / thermal: https://developer.android.com/reference/android/os/PowerManager
 * - Doze / App Standby: https://developer.android.com/training/monitoring-device-state/doze-standby
 */
@Singleton
class PowerGuard @Inject constructor(
    @ApplicationContext private val context: Context
) : PowerStateProvider {

    /** Point-in-time snapshot; never throws. */
    override fun snapshot(): PowerSnapshot {
        val power = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
        val batterySaver = runCatching { power?.isPowerSaveMode == true }.getOrDefault(false)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { power?.currentThermalStatus ?: -1 }.getOrDefault(-1)
        } else {
            -1
        }
        val exempt = runCatching {
            power?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)

        var level = -1
        var charging = false
        try {
            // A null receiver reads the sticky ACTION_BATTERY_CHANGED broadcast (no registration).
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (rawLevel >= 0 && scale > 0) {
                    level = (rawLevel * 100) / scale
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                charging = plugged != 0 ||
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reading battery state failed", e)
        }

        return PowerSnapshot(
            levelPercent = level,
            charging = charging,
            batterySaver = batterySaver,
            thermalStatus = thermal,
            batteryOptimizationExempt = exempt
        )
    }

    /**
     * Verdict for background work. Pass `essential = true` only for a run the user explicitly
     * requested: the guard never defers those.
     */
    fun verdict(essential: Boolean = false): PowerVerdict =
        PowerGuardPolicy.evaluate(snapshot(), essential)

    /** True when non-essential periodic work must be skipped right now. */
    fun shouldDefer(): Boolean = verdict(essential = false).defer

    /** One-line honest reason for the log, or null when work may proceed. */
    fun deferReason(): String? = verdict(essential = false).reason

    /**
     * Human-readable Russian report for `power_guard_status` / `self_diagnostics`. It states the
     * unknown signals explicitly instead of showing a fake number.
     */
    fun statusReport(): String {
        val s = snapshot()
        val v = PowerGuardPolicy.evaluate(s, essential = false)
        val level = if (s.levelPercent >= 0) "${s.levelPercent}%" else "неизвестно"
        val charging = when {
            s.levelPercent < 0 -> "неизвестно"
            s.charging -> "заряжается"
            else -> "не заряжается"
        }
        val decision = when (v.action) {
            PowerAction.ALLOW -> "необязательная фоновая работа: разрешена"
            PowerAction.DEFER -> "необязательная фоновая работа: ОТЛОЖЕНА (${v.reason})"
        }
        return buildString {
            appendLine("питание: заряд $level, $charging")
            appendLine("энергосбережение: ${yesNo(s.batterySaver)}, " +
                "нагрев: ${PowerGuardPolicy.thermalLabel(s.thermalStatus)}")
            appendLine("оптимизация батареи отключена для приложения: ${yesNo(s.batteryOptimizationExempt)}")
            append(decision)
        }
    }

    private fun yesNo(value: Boolean): String = if (value) "да" else "нет"

    companion object {
        private const val TAG = "Andharness"
    }
}
