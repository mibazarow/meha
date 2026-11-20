package com.andharness.ru.data.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.domain.automation.AutomationEvent
import com.andharness.ru.domain.automation.AutomationRule
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.automation.RuleEngine
import com.andharness.ru.domain.automation.TriggerConfigs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process source for the `wifi_open` automation trigger (backlog «Event-driven agent task»).
 *
 * While at least one enabled `wifi_open` rule exists it performs a bounded Wi-Fi scan
 * ([WifiManager.startScan] followed by [WifiManager.getScanResults], the same pair the `wifi_scan`
 * tool uses) and, for every enabled rule, asks the pure layer
 * ([TriggerConfigs.openNetworksToReport]) which visible OPEN networks are new. A newly seen open
 * network marks the rule as fired and dispatches one [AutomationEvent] with `value` = the first new
 * SSID and `payload` = the new networks as `"ssid=…, bssid=…"` entries joined by `; ` (documented
 * on [scanOnce]).
 *
 * Reporting is bounded three ways: per-BSSID dedupe (a BSSID already reported for a rule is not
 * reported again, see [MAX_REMEMBERED_BSSIDS] for the cap), the rule's `min_interval_sec` debounce,
 * and the global [com.andharness.ru.domain.automation.RuleRateLimiter] the engine applies.
 *
 * Honest limits (same class as the BLE/sound/geo monitors):
 * - it only runs while the app process is alive — no foreground service, so in Doze / after the
 *   process is killed nothing is observed;
 * - it needs `NEARBY_WIFI_DEVICES` on API 31+ and `ACCESS_FINE_LOCATION` below (the same rule as the
 *   `wifi_scan` tool); without it the monitor stays idle and logs once (no crash, no repeated spam);
 * - the OS throttles scans (foreground apps: ~4 scans / 2 minutes), so the cadence is deliberately
 *   slow ([SCAN_INTERVAL_MS]) and `startScan` may still return false; when it does, the last
 *   available `scanResults` are used, exactly as the docs describe;
 * - a `SecurityException`/throttling failure is caught and never crashes the loop.
 *
 * Power guard (update.md idea #19, mirroring [com.andharness.ru.bg.AutomationTimeWorker]): a
 * `wifi_open` rule is non-essential periodic automation, so before every cycle the monitor asks
 * [PowerGuard.deferReason]. When the guard returns a reason (battery saver, critical battery, heat
 * — the same Doze/standby class of conditions documented at
 * https://developer.android.com/training/monitoring-device-state/doze-standby) the cycle is skipped
 * entirely: no Wi-Fi scan is requested and no event is dispatched. The debounce and the per-BSSID
 * dedupe memory are left untouched, so the skipped cycle is not mistaken for a firing.
 *
 * References:
 * - Wi-Fi scanning (`startScan` throttling, `getScanResults` permissions):
 *   https://developer.android.com/develop/connectivity/wifi/wifi-scan
 * - `WifiManager`: https://developer.android.com/reference/android/net/wifi/WifiManager
 * - `ScanResult.capabilities` / `SSID` / `BSSID`:
 *   https://developer.android.com/reference/android/net/wifi/ScanResult
 * - Wi-Fi permissions: https://developer.android.com/develop/connectivity/wifi/wifi-permissions
 */
@Singleton
class OpenWifiMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: RuleEngine,
    private val repository: AutomationRuleRepository,
    private val powerGuard: PowerGuard
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val missingPermissionLogged = AtomicBoolean(false)

    /** Per-rule last firing time (ms), the `min_interval_sec` debounce. */
    private val lastFiredAt = ConcurrentHashMap<Long, Long>()

    /** Per-rule BSSIDs (lower-cased) already reported, so a network fires only once. */
    private val reportedBssids = ConcurrentHashMap<Long, MutableSet<String>>()

    private var job: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        job = scope.launch { loop() }
    }

    /** Stops the scan loop. The monitor can be started again afterwards. */
    fun stop() {
        started.set(false)
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val rules = enabledRules()
            if (rules.isEmpty()) {
                delay(IDLE_REFRESH_MS)
                continue
            }
            if (!wifiPermissionGranted()) {
                if (missingPermissionLogged.compareAndSet(false, true)) {
                    Log.w(
                        TAG,
                        "wifi_open rules are enabled but the Wi-Fi scan permission is not granted — " +
                            "the open-Wi-Fi monitor stays idle"
                    )
                }
                delay(IDLE_REFRESH_MS)
                continue
            }
            runCatching { scanOnce(rules) }
                .onFailure { Log.w(TAG, "wifi_open scan failed: ${it.message}") }
            delay(SCAN_INTERVAL_MS)
        }
    }

    private suspend fun enabledRules(): List<AutomationRule> = runCatching {
        repository.getAll().filter {
            it.enabled && it.triggerType == AutomationSpec.TRIGGER_WIFI_OPEN
        }
    }.getOrDefault(emptyList())

    /**
     * One scan: request a scan, read the latest results, and for every rule report the OPEN
     * networks that are new. Fires at most one event per rule per scan — `value` is the first new
     * SSID (what `{value}` renders) and `payload` lists every new network as `"ssid=…, bssid=…"`.
     */
    @Suppress("DEPRECATION")
    private fun scanOnce(rules: List<AutomationRule>) {
        // Power guard (update.md idea #19): a `wifi_open` rule is non-essential periodic
        // automation. While the guard defers, skip this cycle before the (throttled) Wi-Fi scan
        // and dispatch nothing — the debounce/dedupe state stays untouched for the next allowed
        // cycle.
        val deferReason = powerGuard.deferReason()
        if (deferReason != null) {
            Log.d(TAG, "wifi_open scan skipped by the power guard: $deferReason")
            return
        }

        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        if (!wm.isWifiEnabled) return

        try {
            // May be rejected by the OS (throttling / idle): the return value is intentionally
            // ignored because even a rejected request leaves the last results available below.
            wm.startScan()
        } catch (e: SecurityException) {
            Log.w(TAG, "wifi_open scan rejected: ${e.message}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "wifi_open scan failed: ${e.message}")
            return
        }

        val results = try {
            wm.scanResults
        } catch (e: SecurityException) {
            Log.w(TAG, "wifi_open scan results denied: ${e.message}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "wifi_open scan results failed: ${e.message}")
            return
        } ?: return
        if (results.isEmpty()) return

        val observations = results.map { result ->
            TriggerConfigs.OpenWifiObservation(
                ssid = result.SSID.orEmpty(),
                bssid = result.BSSID.orEmpty(),
                capabilities = result.capabilities
            )
        }
        val now = System.currentTimeMillis()

        rules.forEach { rule ->
            val spec = TriggerConfigs.parseOpenWifi(rule.triggerConfig) ?: return@forEach
            if (!TriggerConfigs.openWifiDebounceElapsed(now, lastFiredAt[rule.id], spec.minIntervalSec)) {
                return@forEach
            }

            val reported = reportedBssids.getOrPut(rule.id) { mutableSetOf() }
            val fresh = TriggerConfigs.openNetworksToReport(observations, spec, reported)
            if (fresh.isEmpty()) return@forEach

            if (reported.size + fresh.size > MAX_REMEMBERED_BSSIDS) reported.clear()
            fresh.forEach { reported.add(it.bssid.lowercase()) }
            lastFiredAt[rule.id] = now

            engine.dispatch(
                AutomationEvent(
                    triggerType = AutomationSpec.TRIGGER_WIFI_OPEN,
                    ruleId = rule.id,
                    value = fresh.first().ssid,
                    payload = fresh.joinToString("; ") { "ssid=${it.ssid}, bssid=${it.bssid}" }
                )
            )
        }
    }

    /**
     * The Wi-Fi scan permission on this API level, mirroring the `wifi_scan` tool: API 31+ needs
     * `NEARBY_WIFI_DEVICES`, API <= 30 needs `ACCESS_FINE_LOCATION`.
     * https://developer.android.com/develop/connectivity/wifi/wifi-permissions
     */
    private fun wifiPermissionGranted(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "Andharness"

        /** Re-check for enabled rules / permission this often when there is nothing to do. */
        private const val IDLE_REFRESH_MS = 30_000L

        /**
         * Scan cadence. Android throttles foreground scans to ~4 per 2 minutes (1 per 30 minutes in
         * the background), so one scan every 2 minutes stays under the foreground limit and respects
         * the OS scan-throttling policy.
         * https://developer.android.com/develop/connectivity/wifi/wifi-scan
         */
        private const val SCAN_INTERVAL_MS = 120_000L

        /** Cap on remembered BSSIDs per rule; when exceeded the dedupe memory is reset. */
        private const val MAX_REMEMBERED_BSSIDS = 200
    }
}
