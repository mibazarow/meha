package com.andharness.ru.data.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
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
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * In-process source for the health automation triggers (health checklist): `heart_rate_threshold`,
 * `steps_reached`, `no_movement` and the honest `sleep_window` heuristic.
 *
 * It follows the same shape as [OpenWifiMonitor]: it only runs while the app process is alive, polls
 * the enabled rules itself, uses the pure [TriggerConfigs] state machines for every decision, and
 * dispatches one [AutomationEvent] per firing. No new dependency — only the platform
 * [SensorManager].
 *
 * Sensors (official docs):
 * - `TYPE_HEART_RATE` requires `BODY_SENSORS`; `TYPE_STEP_COUNTER` requires `ACTIVITY_RECOGNITION`
 *   (API 29+); `TYPE_SIGNIFICANT_MOTION` / `TYPE_ACCELEROMETER` need no runtime permission.
 *   https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion
 *   https://developer.android.com/reference/android/hardware/SensorManager
 * - `TYPE_STEP_COUNTER` is CUMULATIVE since boot (not since the app started), so `steps_reached`
 *   stores a baseline the first time it sees a value and reports a DELTA from it.
 *   https://developer.android.com/reference/android/hardware/Sensor#TYPE_STEP_COUNTER
 * - `TYPE_SIGNIFICANT_MOTION` is a one-shot trigger sensor: after each event it must be re-requested
 *   with `requestTriggerSensor` (it can be called from the main-looper thread only).
 *   https://developer.android.com/reference/android/hardware/SensorManager#requestTriggerSensor(android.hardware.TriggerEventListener,%20android.hardware.Sensor)
 *
 * Honest limits:
 * - alive-process only (no foreground service) — nothing is observed after the process dies;
 * - HEART RATE / STEPS are skipped (logged once) when the sensor is absent or its permission is not
 *   granted; `automation_rule_create` already rejects a rule whose sensor is absent;
 * - `no_movement` and `sleep_window` treat "still" as "accelerometer magnitude within
 *   [MOTION_THRESHOLD] of gravity for at least the window" (plus, when present, the significant-motion
 *   trigger) — this is a stillness heuristic, NOT a posture/activity classifier;
 * - `sleep_window` is explicitly a HEURISTIC (time window + screen off + still). Android has no public
 *   sleep-stage sensor, so it must never be presented as sleep tracking.
 */
@Singleton
class HealthSensorMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: RuleEngine,
    private val repository: AutomationRuleRepository,
    private val powerGuard: PowerGuard
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    private val missingPermissionLogged = AtomicBoolean(false)

    /** Last observed motion (monotonic ms), fed by the accelerometer and/or significant motion. */
    private val lastMotionElapsedMs = AtomicLong(0L)

    private val heartTrackers = ConcurrentHashMap<Long, TriggerConfigs.HeartRateDwellTracker>()
    private val noMovementTrackers = ConcurrentHashMap<Long, TriggerConfigs.NoMovementTracker>()
    private val stepSpecs = ConcurrentHashMap<Long, TriggerConfigs.StepsSpec>()
    private val stepBaselines = ConcurrentHashMap<Long, Long>()

    /** Per-rule "sleep already reported for this continuous period" flag. */
    private val sleepArmed = ConcurrentHashMap<Long, Boolean>()

    private var heartListener: SensorEventListener? = null
    private var stepListener: SensorEventListener? = null
    private var accelListener: SensorEventListener? = null
    private var significantMotionListener: TriggerEventListener? = null
    private var significantMotionSensor: Sensor? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        lastMotionElapsedMs.set(SystemClock.elapsedRealtime())
        job = scope.launch { loop() }
    }

    /** Stops the monitor and releases every sensor registration. Safe to start again afterwards. */
    fun stop() {
        started.set(false)
        job?.cancel()
        job = null
        detachAll()
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val rules = enabledHealthRules()
            if (rules.isEmpty()) {
                detachAll()
                delay(IDLE_REFRESH_MS)
                continue
            }
            syncState(rules)
            ensureListeners(rules)
            if (powerGuard.deferReason() == null) {
                evaluateNoMovement(rules)
                evaluateSleep(rules)
            } else {
                Log.d(TAG, "Health sensor checks skipped by the power guard")
            }
            delay(TICK_MS)
        }
    }

    private suspend fun enabledHealthRules(): List<AutomationRule> = runCatching {
        repository.getAll().filter { it.enabled && it.triggerType in HEALTH_TRIGGERS }
    }.getOrDefault(emptyList())

    /** (Re)builds the pure per-rule state machines so parsing happens once per rule, not per tick. */
    private fun syncState(rules: List<AutomationRule>) {
        val now = SystemClock.elapsedRealtime()

        val heartIds = rules.filter { it.triggerType == AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD }
            .mapTo(HashSet()) { it.id }
        heartTrackers.keys.retainAll(heartIds)
        rules.filter { it.triggerType == AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD }.forEach { rule ->
            if (!heartTrackers.containsKey(rule.id)) {
                TriggerConfigs.parseHeartRate(rule.triggerConfig)?.let {
                    heartTrackers[rule.id] = TriggerConfigs.HeartRateDwellTracker(it)
                }
            }
        }

        val stepIds = rules.filter { it.triggerType == AutomationSpec.TRIGGER_STEPS_REACHED }
            .mapTo(HashSet()) { it.id }
        stepSpecs.keys.retainAll(stepIds)
        stepBaselines.keys.retainAll(stepIds)
        rules.filter { it.triggerType == AutomationSpec.TRIGGER_STEPS_REACHED }.forEach { rule ->
            if (!stepSpecs.containsKey(rule.id)) {
                TriggerConfigs.parseSteps(rule.triggerConfig)?.let { stepSpecs[rule.id] = it }
            }
        }

        val noMoveIds = rules.filter { it.triggerType == AutomationSpec.TRIGGER_NO_MOVEMENT }
            .mapTo(HashSet()) { it.id }
        noMovementTrackers.keys.retainAll(noMoveIds)
        rules.filter { it.triggerType == AutomationSpec.TRIGGER_NO_MOVEMENT }.forEach { rule ->
            if (!noMovementTrackers.containsKey(rule.id)) {
                TriggerConfigs.parseNoMovement(rule.triggerConfig)?.let {
                    TriggerConfigs.NoMovementTracker(it).also { tracker ->
                        // Baseline = subscribe time: "no motion" counts from now, not from boot.
                        tracker.onMotion(now)
                        noMovementTrackers[rule.id] = tracker
                    }
                }
            }
        }

        val sleepIds = rules.filter { it.triggerType == AutomationSpec.TRIGGER_SLEEP_WINDOW }
            .mapTo(HashSet()) { it.id }
        sleepArmed.keys.retainAll(sleepIds)
        rules.filter { it.triggerType == AutomationSpec.TRIGGER_SLEEP_WINDOW }.forEach { rule ->
            sleepArmed.putIfAbsent(rule.id, true)
        }
    }

    // ------------------------------------------------------------------ listeners

    private fun ensureListeners(rules: List<AutomationRule>) {
        val needHeart = rules.any { it.triggerType == AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD }
        val needSteps = rules.any { it.triggerType == AutomationSpec.TRIGGER_STEPS_REACHED }
        val needMotion = rules.any {
            it.triggerType == AutomationSpec.TRIGGER_NO_MOVEMENT ||
                it.triggerType == AutomationSpec.TRIGGER_SLEEP_WINDOW
        }
        syncHeartListener(needHeart)
        syncStepListener(needSteps)
        syncMotionListeners(needMotion)
    }

    private fun syncHeartListener(need: Boolean) {
        val sm = sensorManager()
        if (!need) {
            heartListener?.let { runCatching { sm?.unregisterListener(it) } }
            heartListener = null
            return
        }
        if (heartListener != null) return
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return
        if (!granted(Manifest.permission.BODY_SENSORS)) {
            logMissingPermissionOnce("BODY_SENSORS (пульс)")
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val bpm = event.values.firstOrNull()?.toInt() ?: return
                onHeartSample(bpm)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler) == true) {
            heartListener = listener
        }
    }

    private fun syncStepListener(need: Boolean) {
        val sm = sensorManager()
        if (!need) {
            stepListener?.let { runCatching { sm?.unregisterListener(it) } }
            stepListener = null
            return
        }
        if (stepListener != null) return
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        if (!granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            logMissingPermissionOnce("ACTIVITY_RECOGNITION (шаги)")
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val current = event.values.firstOrNull()?.toLong() ?: return
                onStepSample(current)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler) == true) {
            stepListener = listener
        }
    }

    /**
     * Motion signal for `no_movement` / `sleep_window`: the accelerometer (deviation from gravity
     * means movement) plus, when the hardware has it, the one-shot significant-motion trigger. Both
     * simply stamp [lastMotionElapsedMs]; the pure trackers decide what to fire.
     */
    private fun syncMotionListeners(need: Boolean) {
        val sm = sensorManager()
        if (!need) {
            accelListener?.let { runCatching { sm?.unregisterListener(it) } }
            accelListener = null
            cancelSignificantMotion()
            return
        }
        if (accelListener == null) {
            sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val v = event.values
                        if (v.size < 3) return
                        val magnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                        if (abs(magnitude - SensorManager.GRAVITY_EARTH) > MOTION_THRESHOLD) {
                            lastMotionElapsedMs.set(SystemClock.elapsedRealtime())
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                if (sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)) {
                    accelListener = listener
                }
            }
        }
        if (significantMotionListener == null) {
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
            val listener = object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent) {
                    lastMotionElapsedMs.set(SystemClock.elapsedRealtime())
                    // One-shot: re-request so the next motion is still observed.
                    requestSignificantMotion()
                }
            }
            significantMotionListener = listener
            significantMotionSensor = sensor
            requestSignificantMotion()
        }
    }

    private fun requestSignificantMotion() {
        val sm = sensorManager() ?: return
        val sensor = significantMotionSensor ?: return
        val listener = significantMotionListener ?: return
        // requestTriggerSensor must run on a thread with a Looper.
        handler.post { runCatching { sm.requestTriggerSensor(listener, sensor) } }
    }

    private fun cancelSignificantMotion() {
        val listener = significantMotionListener ?: return
        val sensor = significantMotionSensor
        if (sensor != null) {
            runCatching { sensorManager()?.cancelTriggerSensor(listener, sensor) }
        }
        significantMotionListener = null
        significantMotionSensor = null
    }

    private fun detachAll() {
        val sm = sensorManager()
        heartListener?.let { runCatching { sm?.unregisterListener(it) } }
        stepListener?.let { runCatching { sm?.unregisterListener(it) } }
        accelListener?.let { runCatching { sm?.unregisterListener(it) } }
        heartListener = null
        stepListener = null
        accelListener = null
        cancelSignificantMotion()
    }

    // ------------------------------------------------------------------ decision + dispatch

    private fun onHeartSample(bpm: Int) {
        val now = SystemClock.elapsedRealtime()
        heartTrackers.forEach { (ruleId, tracker) ->
            if (tracker.onSample(bpm, now)) {
                engine.dispatch(
                    AutomationEvent(
                        triggerType = AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD,
                        ruleId = ruleId,
                        value = bpm.toString()
                    )
                )
            }
        }
    }

    private fun onStepSample(current: Long) {
        stepSpecs.forEach { (ruleId, spec) ->
            val baseline = stepBaselines.putIfAbsent(ruleId, current)
            if (baseline == null) return@forEach // baseline captured on the first sample
            if (current < baseline) {
                // The cumulative counter went backwards (device reboot) — re-capture the baseline.
                stepBaselines[ruleId] = current
                return@forEach
            }
            val delta = spec.delta(baseline, current)
            if (delta >= spec.target) {
                engine.dispatch(
                    AutomationEvent(
                        triggerType = AutomationSpec.TRIGGER_STEPS_REACHED,
                        ruleId = ruleId,
                        value = delta.toString()
                    )
                )
                // Re-arm: the next target counts from the current step count.
                stepBaselines[ruleId] = current
            }
        }
    }

    private fun evaluateNoMovement(rules: List<AutomationRule>) {
        val now = SystemClock.elapsedRealtime()
        rules.filter { it.triggerType == AutomationSpec.TRIGGER_NO_MOVEMENT }.forEach { rule ->
            val tracker = noMovementTrackers[rule.id] ?: return@forEach
            if (tracker.shouldFire(now)) {
                engine.dispatch(
                    AutomationEvent(
                        triggerType = AutomationSpec.TRIGGER_NO_MOVEMENT,
                        ruleId = rule.id,
                        value = tracker.idleMinutes(now).toString()
                    )
                )
            }
        }
    }

    private fun evaluateSleep(rules: List<AutomationRule>) {
        val minuteOfDay = minuteOfDay()
        val screenOff = !powerManager().isInteractive
        val stillMs = SystemClock.elapsedRealtime() - lastMotionElapsedMs.get()
        val still = stillMs >= STILL_WINDOW_MS
        rules.filter { it.triggerType == AutomationSpec.TRIGGER_SLEEP_WINDOW }.forEach { rule ->
            val spec = TriggerConfigs.parseSleepWindow(rule.triggerConfig) ?: return@forEach
            val sleeping = spec.isSleeping(minuteOfDay, screenOff, still)
            val armed = sleepArmed[rule.id] != false
            when {
                sleeping && armed -> {
                    sleepArmed[rule.id] = false
                    engine.dispatch(
                        AutomationEvent(
                            triggerType = AutomationSpec.TRIGGER_SLEEP_WINDOW,
                            ruleId = rule.id,
                            value = TriggerConfigs.formatMinuteOfDay(spec.startMinute) + "–" +
                                TriggerConfigs.formatMinuteOfDay(spec.endMinute),
                            payload = "эвристика: экран " + (if (screenOff) "выключен" else "включён") +
                                ", телефон неподвижен ${stillMs / 60_000} мин (не измерение сна)"
                        )
                    )
                }

                !sleeping -> sleepArmed[rule.id] = true
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun sensorManager(): SensorManager? =
        context.applicationContext.getSystemService(SensorManager::class.java)

    private fun powerManager(): PowerManager =
        context.applicationContext.getSystemService(PowerManager::class.java)

    private fun minuteOfDay(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun logMissingPermissionOnce(what: String) {
        if (missingPermissionLogged.compareAndSet(false, true)) {
            Log.w(TAG, "Health automation needs the $what permission — the sensor stays idle")
        }
    }

    companion object {
        private const val TAG = "Andharness"

        /** Re-check for enabled rules / release sensors this often when there is nothing to do. */
        private const val IDLE_REFRESH_MS = 30_000L

        /** Evaluation cadence for the time-window based triggers (`no_movement`, `sleep_window`). */
        private const val TICK_MS = 15_000L

        /** Accelerometer deviation from gravity (m/s²) that counts as movement. */
        private const val MOTION_THRESHOLD = 0.6

        /** How still the phone must be for `sleep_window` to consider it idle. */
        private const val STILL_WINDOW_MS = 5 * 60_000L

        private val HEALTH_TRIGGERS = setOf(
            AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD,
            AutomationSpec.TRIGGER_STEPS_REACHED,
            AutomationSpec.TRIGGER_NO_MOVEMENT,
            AutomationSpec.TRIGGER_SLEEP_WINDOW
        )
    }
}
