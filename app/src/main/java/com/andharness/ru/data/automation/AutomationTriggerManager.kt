package com.andharness.ru.data.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.andharness.ru.bg.AutomationCalendarWorker
import com.andharness.ru.domain.automation.AutomationEvent
import com.andharness.ru.domain.automation.AutomationEventRepository
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationScheduler
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.automation.RuleEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires every non-manifest automation trigger to a REAL source while the app process is alive
 * (update.md §11.6):
 *
 * - power/screen triggers via a dynamically registered [AutomationRuntimeReceiver];
 * - `network` / `wifi_connected` via [ConnectivityManager.registerDefaultNetworkCallback];
 * - `app_opened` / `notification` via [AutomationEventBus], fed by the accessibility and
 *   notification-listener services;
 * - `ble_presence` / `sound_event` / `geo_distance` / `wifi_open` via the in-process monitors of
 *   idea #18, which poll the rule list themselves and only run while the process is alive.
 *
 * It also arms the persisted `time` rules through [AutomationScheduler] on start. It is idempotent —
 * safe to call from [android.app.Application.onCreate] and from the boot receiver.
 *
 * References:
 * - https://developer.android.com/develop/background-work/background-tasks/broadcasts
 * - https://developer.android.com/reference/android/net/ConnectivityManager#registerDefaultNetworkCallback(android.net.ConnectivityManager.NetworkCallback)
 */
@Singleton
class AutomationTriggerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: RuleEngine,
    private val repository: AutomationRuleRepository,
    private val eventRepository: AutomationEventRepository,
    private val scheduler: AutomationScheduler,
    private val blePresenceMonitor: BlePresenceMonitor,
    private val soundEventMonitor: SoundEventMonitor,
    private val geoDistanceMonitor: GeoDistanceMonitor,
    private val openWifiMonitor: OpenWifiMonitor,
    private val healthSensorMonitor: HealthSensorMonitor
) {

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runtimeReceiver: AutomationRuntimeReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        registerRuntimeReceiver()
        registerNetworkCallback()
        AutomationEventBus.setListener(object : AutomationEventBus.Listener {
            override fun onWindowStateChanged(packageName: String) {
                engine.dispatch(
                    AutomationEvent(
                        triggerType = AutomationSpec.TRIGGER_APP_OPENED,
                        packageName = packageName
                    )
                )
            }

            override fun onNotificationPosted(packageName: String, title: String, text: String) {
                engine.dispatch(
                    AutomationEvent(
                        triggerType = AutomationSpec.TRIGGER_NOTIFICATION,
                        packageName = packageName,
                        title = title,
                        text = text
                    )
                )
            }
        })
        scope.launch {
            runCatching { disableInvalidRules() }
                .onFailure { Log.e(TAG, "Sanitizing invalid automation rules failed", it) }
            // Bounded retention for the firing-event log: enforced once at app start
            // on the IO scope. Cheap — the table is capped at ~500 rows, so both deletes are indexed.
            runCatching { eventRepository.prune() }
                .onFailure { Log.e(TAG, "Pruning the automation event log failed", it) }
            runCatching {
                scheduler.rescheduleAll(
                    repository.getAll().filter {
                        it.enabled && it.triggerType == AutomationSpec.TRIGGER_TIME
                    }
                )
            }.onFailure { Log.e(TAG, "Arming time automation rules failed", it) }
        }
        // update.md idea #18: the deep triggers that need an in-process observer / periodic check.
        blePresenceMonitor.start()
        soundEventMonitor.start()
        geoDistanceMonitor.start()
        openWifiMonitor.start()
        healthSensorMonitor.start()
        refresh()
    }

    /**
     * Re-evaluates which OS-scheduled trigger sources must be armed. Called at start and after a
     * rule is created, toggled or deleted, so a new `calendar_event` rule takes effect without an
     * app restart. The BLE/sound monitors poll the rule list themselves.
     */
    fun refresh() {
        scope.launch {
            runCatching { armCalendarCheck() }
                .onFailure { Log.e(TAG, "Arming the calendar automation check failed", it) }
        }
    }

    /**
     * Sanitises already-persisted rules (update.md §11.6): a rule created before the validators were
     * tightened can still be `enabled` in the database — a `sound_event` rule with a `-100 dBFS`
     * threshold is the concrete case that spammed notifications. Such rules are disabled once at
     * start so they cannot linger enabled; the runtime [RuleRateLimiter] is the second line of
     * defence. Runs in the start coroutine, before time rules are (re)armed, so nothing invalid is
     * scheduled. Validation is pure ([AutomationSpec]), so this stays cheap.
     */
    private suspend fun disableInvalidRules() {
        repository.getAll().filter { it.enabled }.forEach { rule ->
            val triggerError = AutomationSpec.validateTrigger(rule.triggerType, rule.triggerConfig)
            val actionError = AutomationSpec.validateAction(rule.actionType, rule.actionConfig)
            if (triggerError != null || actionError != null) {
                Log.w(
                    TAG,
                    "Disabling invalid automation rule ${rule.id} (${rule.name}): " +
                        (triggerError ?: actionError)
                )
                runCatching { repository.setEnabled(rule.id, false) }
                    .onFailure { Log.e(TAG, "Disabling invalid automation rule ${rule.id} failed", it) }
            }
        }
    }

    private suspend fun armCalendarCheck() {
        val hasCalendarRules = repository.getAll().any {
            it.enabled && it.triggerType == AutomationSpec.TRIGGER_CALENDAR_EVENT
        }
        val workManager = WorkManager.getInstance(context)
        if (hasCalendarRules) {
            val request = PeriodicWorkRequestBuilder<AutomationCalendarWorker>(
                CALENDAR_CHECK_MINUTES,
                TimeUnit.MINUTES
            ).build()
            workManager.enqueueUniquePeriodicWork(
                AutomationCalendarWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } else {
            workManager.cancelUniqueWork(AutomationCalendarWorker.UNIQUE_NAME)
        }
    }

    private fun registerRuntimeReceiver() {
        val receiver = AutomationRuntimeReceiver { trigger ->
            engine.dispatch(AutomationEvent(triggerType = trigger))
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_POWER_CONNECTED)
            addAction(android.content.Intent.ACTION_BATTERY_LOW)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        try {
            // RECEIVER_NOT_EXPORTED: these are system broadcasts, which are still delivered to a
            // non-exported receiver; app-private avoids exposing the trigger surface to other apps.
            // https://developer.android.com/about/versions/14/behavior-changes-14#runtime-receivers-exported
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            runtimeReceiver = receiver
        } catch (e: Exception) {
            Log.e(TAG, "Registering automation runtime receiver failed", e)
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = try {
                    cm.getNetworkCapabilities(network)
                } catch (e: Exception) {
                    null
                }
                engine.dispatch(AutomationEvent(triggerType = AutomationSpec.TRIGGER_NETWORK))
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    engine.dispatch(AutomationEvent(triggerType = AutomationSpec.TRIGGER_WIFI_CONNECTED))
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "Registering default network callback failed", e)
        }
    }

    companion object {
        private const val TAG = "Andharness"

        /** WorkManager's minimum periodic interval; the calendar check is inherently inexact. */
        private const val CALENDAR_CHECK_MINUTES = 15L
    }
}
