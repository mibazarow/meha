package com.andharness.ru.data.objective

import android.util.Log
import com.andharness.ru.domain.diagnostics.PowerGuardPolicy
import com.andharness.ru.domain.diagnostics.PowerStateProvider
import com.andharness.ru.domain.objective.Objective
import com.andharness.ru.domain.objective.ObjectiveConcurrencyLimiter
import com.andharness.ru.domain.objective.ObjectiveConcurrencyPolicy
import com.andharness.ru.domain.objective.ObjectiveRunGate
import com.andharness.ru.domain.objective.ObjectiveRunOutcome
import com.andharness.ru.domain.objective.ObjectiveRunReport
import com.andharness.ru.domain.objective.ObjectiveRunner
import com.andharness.ru.domain.objective.ObjectiveRules
import com.andharness.ru.domain.objective.ObjectiveStopReport
import com.andharness.ru.domain.objective.ObjectiveWorkItem
import com.andharness.ru.domain.objective.ObjectiveWorkState
import com.andharness.ru.domain.objective.ObjectiveWorkersStatus
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded, fair coordinator that lets several long-running objectives progress "in parallel"
 * (checklist §2 «Параллельные задачи/фоновые цели»).
 *
 * How "parallel" is bounded and tracked:
 * - at most `concurrencyLimit` objectives (configured 1..3, default 2) run at once;
 *   [ObjectiveConcurrencyLimiter] holds that bound and a FIFO queue for the rest;
 * - the effective limit is reduced to 1 on a degraded device (battery saver / low battery / warm
 *   thermal, [ObjectiveConcurrencyPolicy]) and the whole tick is skipped under CRITICAL power
 *   (the vetted [PowerGuardPolicy] `defer`);
 * - RUNNING and QUEUED are tracked explicitly (the limiter's sets) and published through [status];
 *   they are process-scoped on purpose — a run cannot survive a process restart, so nothing is ever
 *   reported as running after a restart (the periodic [com.andharness.ru.bg.ObjectiveWorker]
 *   re-enqueues eligible objectives instead);
 * - a terminal (DONE/CANCELLED), PAUSED, BLOCKED, budget-exhausted or unconfigured objective is
 *   never run, and no progress is faked ([ObjectiveRunGate]).
 *
 * Each admitted objective is driven by ONE bounded [ObjectiveRunner.runStep] on this singleton's
 * application-lifetime scope, so a `objective_run` can return immediately while the work continues.
 */
@Singleton
class ObjectiveRunCoordinator @Inject constructor(
    private val store: ObjectiveStore,
    private val runner: ObjectiveRunner,
    private val settings: LlmSettingsStore,
    private val power: PowerStateProvider,
    private val workStore: ObjectiveWorkStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limiter = ObjectiveConcurrencyLimiter(workStore.maxConcurrency())

    /** Objective id → its live background step. */
    private val jobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var effectiveLimit: Int = limiter.limit

    @Volatile
    private var llmConfigured: Boolean = true

    @Volatile
    private var note: String = ""

    private val _status = MutableStateFlow(buildStatus())
    val status: StateFlow<ObjectiveWorkersStatus> = _status.asStateFlow()

    /**
     * One coordinator tick: applies the power guard, enforces the (downgraded) limit, enqueues every
     * eligible ACTIVE objective in fair round-robin order and starts the admitted ones.
     *
     * @param essential true only for a run the user explicitly requested (`objective_run`): such a
     *   tick ignores the CRITICAL-power *defer* (matching `PowerGuardPolicy.essential`) but still
     *   honours the concurrency bound.
     */
    suspend fun tick(essential: Boolean = false, llmConfiguredOverride: Boolean? = null): ObjectiveWorkersStatus {
        val now = System.currentTimeMillis()
        val snapshot = runCatching { power.snapshot() }.getOrNull()
        llmConfigured = llmConfiguredOverride
            ?: runCatching { settings.isConfigured().first() }.getOrDefault(false)

        val verdict = snapshot?.let { PowerGuardPolicy.evaluate(it, essential) }
        if (verdict != null && verdict.defer) {
            effectiveLimit = 0
            note = "охрана питания отложила фоновую работу: ${verdict.reason ?: "критическое питание"}"
            workStore.recordTick(now, null)
            publish()
            return _status.value
        }

        val configured = workStore.maxConcurrency()
        effectiveLimit = if (snapshot != null) {
            ObjectiveConcurrencyPolicy.effectiveLimit(configured, snapshot)
        } else {
            configured
        }
        val degraded = snapshot?.let { ObjectiveConcurrencyPolicy.degradedReason(it) }
        note = if (degraded != null) "понижено до 1: $degraded" else ""

        // Raising the limit may admit queued objectives right away.
        val admitted = limiter.setLimit(effectiveLimit)

        // A running objective that became ineligible (paused/terminal/blocked) is stopped.
        for (id in limiter.runningIds()) {
            val current = store.get(id)
            if (current == null || !isAutoRunnable(current, llmConfigured)) {
                limiter.cancel(id)
                jobs.remove(id)?.cancel()
            }
        }

        // Fair round-robin: fewest background runs first, then least recently worked.
        val candidates = store.list()
            .filter { isAutoRunnable(it, llmConfigured) }
            .sortedWith(compareBy({ it.backgroundRuns }, { it.lastWorkedAt }))
        for (objective in candidates) {
            if (limiter.request(objective.id) && !jobs.containsKey(objective.id)) launch(objective.id)
        }
        for (id in admitted) {
            val objective = store.get(id)
            if (objective != null && isAutoRunnable(objective, llmConfigured) && !jobs.containsKey(id)) {
                launch(id)
            }
        }

        workStore.recordTick(now, null)
        publish()
        return _status.value
    }

    /**
     * Handles `objective_run`: with a [ref] it validates that specific objective and, if runnable,
     * kicks an essential tick; with `null` it advances all eligible objectives. Honest refusals
     * (no LLM / terminal / blocked / budget) are returned without admitting anything.
     */
    suspend fun runRequested(ref: String?): ObjectiveRunReport {
        val llm = runCatching { settings.isConfigured().first() }.getOrDefault(false)
        if (ref == null) {
            if (!llm) {
                publish()
                return ObjectiveRunReport(ObjectiveRunOutcome.NO_LLM, null, _status.value)
            }
            val status = tick(essential = false, llmConfiguredOverride = llm)
            return ObjectiveRunReport(ObjectiveRunOutcome.STARTED, null, status)
        }
        val objective = store.get(ref)
            ?: return ObjectiveRunReport(ObjectiveRunOutcome.NOT_FOUND, null, _status.value)
        val gate = ObjectiveRunGate.evaluate(
            status = objective.status,
            blocked = ObjectiveRules.isBlocked(objective),
            llmConfigured = llm,
            deferred = false,
            backgroundRuns = objective.backgroundRuns
        )
        if (gate != ObjectiveRunOutcome.RUNNABLE) {
            return ObjectiveRunReport(gate, objective.id, _status.value)
        }
        val status = tick(essential = true, llmConfiguredOverride = llm)
        val outcome = if (limiter.isRunning(objective.id) || limiter.isQueued(objective.id)) {
            ObjectiveRunOutcome.STARTED
        } else {
            ObjectiveRunOutcome.DEFERRED
        }
        return ObjectiveRunReport(outcome, objective.id, status)
    }

    /** Handles `objective_stop`: stops one objective (by id/title) or all of them. */
    fun stop(ref: String?): ObjectiveStopReport {
        val stopped = mutableListOf<String>()
        if (ref == null) {
            stopped += limiter.runningIds()
            stopped += limiter.queuedIds()
            limiter.clear()
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        } else {
            val objective = store.get(ref)
                ?: return ObjectiveStopReport(emptyList(), _status.value)
            limiter.cancel(objective.id)
            jobs.remove(objective.id)?.cancel()
            stopped += objective.id
        }
        publish()
        return ObjectiveStopReport(stopped, _status.value)
    }

    /** Handles `objective_concurrency_set`: clamps to 1..3, persists it and admits queued work. */
    fun setConcurrency(value: Int): Int {
        val clamped = workStore.setMaxConcurrency(value)
        effectiveLimit = clamped
        for (id in limiter.setLimit(clamped)) {
            val objective = store.get(id)
            if (objective != null && isAutoRunnable(objective, llmConfigured) && !jobs.containsKey(id)) {
                launch(id)
            }
        }
        publish()
        return clamped
    }

    /**
     * Read-only status refresh for `objective_workers_status`: re-reads only the LLM-configured flag
     * and republishes. It NEVER ticks, so merely asking for the status cannot start or stop work.
     */
    suspend fun refreshStatus(): ObjectiveWorkersStatus {
        llmConfigured = runCatching { settings.isConfigured().first() }.getOrDefault(llmConfigured)
        publish()
        return _status.value
    }

    private fun launch(id: String) {
        if (jobs.containsKey(id)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = runner.runStep(id)
                Log.i(TAG, "objective $id → ${result.outcome}: ${result.detail}")
                if (result.outcome == ObjectiveRunOutcome.FAILED ||
                    result.outcome == ObjectiveRunOutcome.BUDGET_EXHAUSTED ||
                    result.outcome == ObjectiveRunOutcome.NO_CHAT
                ) {
                    workStore.recordTick(System.currentTimeMillis(), "цель $id: ${result.detail}")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Log.w(TAG, "objective $id background step failed", t)
                workStore.recordTick(
                    System.currentTimeMillis(),
                    "цель $id: ${t.message ?: t.javaClass.simpleName}"
                )
            } finally {
                jobs.remove(id)
                val next = limiter.release(id)
                if (next != null) {
                    val objective = store.get(next)
                    if (objective != null && isAutoRunnable(objective, llmConfigured)) launch(next)
                }
                publish()
            }
        }
        jobs[id] = job
        job.start()
    }

    private fun isAutoRunnable(objective: Objective, llm: Boolean): Boolean =
        ObjectiveRunGate.isRunnable(
            status = objective.status,
            blocked = ObjectiveRules.isBlocked(objective),
            llmConfigured = llm,
            deferred = false,
            backgroundRuns = objective.backgroundRuns
        )

    private fun publish() {
        _status.value = buildStatus()
    }

    private fun buildStatus(): ObjectiveWorkersStatus {
        val config = workStore.load()
        return ObjectiveWorkersStatus(
            running = limiter.runningIds().mapNotNull { item(it, ObjectiveWorkState.RUNNING) },
            queued = limiter.queuedIds().mapNotNull { item(it, ObjectiveWorkState.QUEUED) },
            concurrencyLimit = config.maxConcurrency,
            effectiveLimit = effectiveLimit,
            lastTickAt = config.lastTickAt,
            lastError = config.lastError,
            llmConfigured = llmConfigured,
            note = note
        )
    }

    private fun item(id: String, state: ObjectiveWorkState): ObjectiveWorkItem? =
        store.get(id)?.let { ObjectiveWorkItem(objectiveId = id, title = it.title, state = state) }

    private companion object {
        const val TAG = "Andharness"
    }
}
