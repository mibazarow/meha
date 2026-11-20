package com.andharness.ru.data.automation

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.andharness.ru.domain.automation.AutomationEvent
import com.andharness.ru.domain.automation.AutomationRule
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.automation.RuleEngine
import com.andharness.ru.domain.automation.TriggerConfigs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process source for the `ble_presence` automation trigger (update.md idea #18).
 *
 * While at least one enabled `ble_presence` rule exists it repeats a bounded BLE scan
 * ([android.bluetooth.le.BluetoothLeScanner.startScan]) and fires a rule when its device is seen at
 * or above the configured RSSI threshold. Firing is debounced per rule so a device that stays in
 * range does not retrigger on every advertisement.
 *
 * Honest limits:
 * - the scan only runs while the app process is alive (like the other runtime triggers);
 * - an unfiltered scan stops while the screen is off, so presence may be missed in Doze;
 * - API 31+ needs BLUETOOTH_SCAN **and** BLUETOOTH_CONNECT, older versions need the location
 *   permission — without them the monitor stays idle and the tool reports the missing access.
 *
 * References:
 * - BLE scanning: https://developer.android.com/develop/connectivity/bluetooth/ble/scanning
 * - `BluetoothLeScanner`: https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner
 * - Bluetooth permission split: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
 */
@Singleton
class BlePresenceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: RuleEngine,
    private val repository: AutomationRuleRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val lastFiredAt = ConcurrentHashMap<Long, Long>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { loop() }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val rules = enabledRules()
            if (rules.isEmpty() || !permissionsGranted()) {
                delay(IDLE_REFRESH_MS)
                continue
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            val scanner = if (adapter != null && adapter.isEnabled) adapter.bluetoothLeScanner else null
            if (scanner == null) {
                delay(IDLE_REFRESH_MS)
                continue
            }
            runCatching { scanOnce(scanner, rules) }
                .onFailure { Log.w(TAG, "BLE presence scan failed: ${it.message}") }
            delay(SCAN_GAP_MS)
        }
    }

    private suspend fun enabledRules(): List<AutomationRule> = runCatching {
        repository.getAll().filter {
            it.enabled && it.triggerType == AutomationSpec.TRIGGER_BLE_PRESENCE
        }
    }.getOrDefault(emptyList())

    private suspend fun scanOnce(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        rules: List<AutomationRule>
    ) {
        val active = AtomicBoolean(true)
        val timeoutSec = rules
            .mapNotNull { TriggerConfigs.parseBlePresence(it.triggerConfig)?.timeoutSec }
            .maxOrNull()
            ?.coerceIn(1, MAX_SCAN_SEC)
            ?: TriggerConfigs.DEFAULT_BLE_TIMEOUT_SEC

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!active.get()) return
                val device = result.device ?: return
                val address = try {
                    device.address
                } catch (_: SecurityException) {
                    return
                }
                val name = try {
                    result.scanRecord?.deviceName
                } catch (_: SecurityException) {
                    null
                } ?: try {
                    device.name
                } catch (_: SecurityException) {
                    null
                }
                val rssi = result.rssi
                val now = System.currentTimeMillis()
                rules.forEach { rule ->
                    val spec = TriggerConfigs.parseBlePresence(rule.triggerConfig) ?: return@forEach
                    if (!spec.matches(address, name, rssi)) return@forEach
                    val previous = lastFiredAt[rule.id]
                    if (previous != null && now - previous < DEBOUNCE_MS) return@forEach
                    lastFiredAt[rule.id] = now
                    engine.dispatch(
                        AutomationEvent(
                            triggerType = AutomationSpec.TRIGGER_BLE_PRESENCE,
                            ruleId = rule.id,
                            value = address,
                            name = name,
                            rssi = rssi
                        )
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE presence scan failed with code $errorCode")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            @Suppress("MissingPermission")
            scanner.startScan(null, settings, callback)
            delay(timeoutSec * 1000L)
        } finally {
            active.set(false)
            runCatching { scanner.stopScan(callback) }
        }
    }

    private fun permissionsGranted(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "Andharness"

        /** Re-check for enabled rules / permissions / Bluetooth this often when there is nothing to do. */
        private const val IDLE_REFRESH_MS = 30_000L

        /** Pause between two bounded scans. */
        private const val SCAN_GAP_MS = 15_000L

        /** Never hold a scan longer than this regardless of the rule's `timeout_sec`. */
        private const val MAX_SCAN_SEC = 60

        /** A rule that already fired for a device does not fire again within this window. */
        private const val DEBOUNCE_MS = 5 * 60_000L
    }
}
