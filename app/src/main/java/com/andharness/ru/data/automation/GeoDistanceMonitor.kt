package com.andharness.ru.data.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * In-process source for the `geo_distance` automation trigger (backlog «Event-driven agent task»).
 *
 * Every [POLL_INTERVAL_MS] it takes one location fix and, for every enabled `geo_distance` rule,
 * compares it with the last point stored for that rule ([GeoDistanceStore]). When the moved
 * great-circle distance reaches the rule's `distance_m` — and the rule's `min_interval_sec` debounce
 * has elapsed — it stores the new point and dispatches an [AutomationEvent].
 *
 * Honest limits (same class as the BLE/sound monitors):
 * - it only runs while the app process is alive; there is no foreground service and no background
 *   location request, so in Doze / when the process is killed nothing is observed;
 * - it needs `ACCESS_FINE_LOCATION`; without it the monitor stays idle and logs once (no crash, no
 *   repeated spam);
 * - fixes whose reported accuracy is worse than half the rule's threshold are ignored, because such
 *   a fix cannot honestly prove the phone moved that far (see [isAccurateEnough]);
 * - Android may return a stale last-known fix when no provider has a fresh one; the monitor prefers
 *   an actively requested fix and only falls back to last-known.
 *
 * Power guard (update.md idea #19, mirroring [com.andharness.ru.bg.AutomationTimeWorker]): a
 * `geo_distance` rule is non-essential periodic automation, so before every cycle the monitor asks
 * [PowerGuard.deferReason]. When the guard returns a reason (battery saver, critical battery, heat
 * — the same Doze/standby class of conditions documented at
 * https://developer.android.com/training/monitoring-device-state/doze-standby) the cycle is skipped
 * entirely: no location fix is requested and no event is dispatched. The debounce baseline is left
 * untouched, so the skipped cycle is not mistaken for a firing.
 *
 * References:
 * - `LocationManager.getLastKnownLocation(provider)` / `requestSingleUpdate(...)`:
 *   https://developer.android.com/reference/android/location/LocationManager
 * - `Location.getAccuracy()` (metres, horizontal 68% confidence):
 *   https://developer.android.com/reference/android/location/Location
 * - Location permissions (and the background-location caveat):
 *   https://developer.android.com/develop/sensors-and-location/location/permissions
 * - Haversine formula: https://en.wikipedia.org/wiki/Haversine_formula
 */
@Singleton
class GeoDistanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: RuleEngine,
    private val repository: AutomationRuleRepository,
    private val powerGuard: PowerGuard
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val missingPermissionLogged = AtomicBoolean(false)
    private val store = GeoDistanceStore(context)
    private var job: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        job = scope.launch { loop() }
    }

    /** Stops the poll loop. The monitor can be started again afterwards. */
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
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                if (missingPermissionLogged.compareAndSet(false, true)) {
                    Log.w(
                        TAG,
                        "geo_distance rules are enabled but ACCESS_FINE_LOCATION is not granted — " +
                            "the location monitor stays idle"
                    )
                }
                delay(IDLE_REFRESH_MS)
                continue
            }
            runCatching { pollOnce(rules) }
                .onFailure { Log.w(TAG, "geo_distance poll failed: ${it.message}") }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun enabledRules(): List<AutomationRule> = runCatching {
        repository.getAll().filter {
            it.enabled && it.triggerType == AutomationSpec.TRIGGER_GEO_DISTANCE
        }
    }.getOrDefault(emptyList())

    private suspend fun pollOnce(rules: List<AutomationRule>) {
        // Power guard (update.md idea #19): a `geo_distance` rule is non-essential periodic
        // automation. While the guard defers, skip this cycle before the expensive location fix
        // and dispatch nothing — the stored baseline stays untouched for the next allowed cycle.
        val deferReason = powerGuard.deferReason()
        if (deferReason != null) {
            Log.d(TAG, "geo_distance poll skipped by the power guard: $deferReason")
            return
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val location = currentLocation(lm) ?: return
        val now = System.currentTimeMillis()

        rules.forEach { rule ->
            val spec = TriggerConfigs.parseGeoDistance(rule.triggerConfig) ?: return@forEach
            if (!isAccurateEnough(location, spec)) return@forEach

            val previous = store.get(rule.id)
            if (previous == null) {
                // First-ever fix for this rule: it is only the baseline, never a firing.
                store.put(
                    GeoDistancePoint(
                        ruleId = rule.id,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        lastFiredAt = 0L
                    )
                )
                return@forEach
            }

            val moved = TriggerConfigs.distanceMeters(
                previous.latitude,
                previous.longitude,
                location.latitude,
                location.longitude
            )
            if (!spec.triggers(moved)) return@forEach

            val debounced = previous.lastFiredAt > 0L &&
                now - previous.lastFiredAt < spec.minIntervalSec * 1000L
            if (debounced) return@forEach

            store.put(
                GeoDistancePoint(
                    ruleId = rule.id,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    lastFiredAt = now
                )
            )
            engine.dispatch(
                AutomationEvent(
                    triggerType = AutomationSpec.TRIGGER_GEO_DISTANCE,
                    ruleId = rule.id,
                    // `value` carries the numeric moved distance in metres so `{value}` renders it
                    // and AutomationSpec.matches can re-check the threshold; `payload` carries the
                    // new coordinates for human-readable templates.
                    value = TriggerConfigs.formatDistance(moved),
                    payload = "lat=${location.latitude}, lon=${location.longitude}"
                )
            )
        }
    }

    /**
     * A fix cannot prove that the phone moved `distance_m` when the fix itself is uncertain by more
     * than half of that: e.g. a ±500 m network fix must not satisfy a 300 m rule. Fixes without a
     * reported accuracy are accepted (nothing to check against).
     */
    private fun isAccurateEnough(location: Location, spec: TriggerConfigs.GeoDistanceSpec): Boolean =
        !location.hasAccuracy() || location.accuracy <= spec.distanceM / 2.0

    /**
     * Returns an actively requested fix (preferred) or, failing that, the newest last-known fix.
     * Returns null when no enabled provider yields a location within [FIX_TIMEOUT_MS].
     */
    private suspend fun currentLocation(lm: LocationManager): Location? {
        val providers = LOCATION_PROVIDERS.filter { provider ->
            runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return null

        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            for (provider in providers) {
                val fix = runCatching { awaitSingleUpdate(lm, provider) }.getOrNull()
                if (fix != null) return@withTimeoutOrNull fix
            }
            null
        }
        if (fresh != null) return fresh

        return providers.firstNotNullOfOrNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }
    }

    /**
     * Bridges one `LocationManager.requestSingleUpdate` callback to a suspend call; the listener is
     * always removed, on both success and cancellation. `requestSingleUpdate` works on every
     * supported API (unlike `getCurrentLocation`, which is API 30+); it is fine that it is deprecated
     * from API 30 because the project targets minSdk 26.
     */
    private suspend fun awaitSingleUpdate(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (!cont.isCompleted) cont.resume(location)
                }

                override fun onProviderEnabled(provider: String) {}

                override fun onProviderDisabled(provider: String) {}

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try {
                @Suppress("MissingPermission")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (_: Exception) {
                lm.removeUpdates(listener)
                if (!cont.isCompleted) cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "Andharness"

        /** The poll cadence; a rule that moves faster than this is still caught on the next tick. */
        private const val POLL_INTERVAL_MS = 60_000L

        /** How often to re-check for enabled rules / permission while there is nothing to do. */
        private const val IDLE_REFRESH_MS = 30_000L

        /** Budget for one actively requested fix before falling back to a last-known location. */
        private const val FIX_TIMEOUT_MS = 8_000L

        /** Preferred order: GPS, then network, then the passive provider. */
        private val LOCATION_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    }
}
