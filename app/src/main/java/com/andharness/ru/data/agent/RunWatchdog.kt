package com.andharness.ru.data.agent

import com.andharness.ru.domain.agent.RunLifecyclePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the "a turn can never run unbounded" guarantee (update.md bug #7): it re-checks the live
 * turn's hard cap and stall timeout and, when [RunLifecyclePolicy.isOverdue] holds, invokes
 * [onOverdue] so the host can reset the published run state honestly. It runs on [scope], i.e.
 * independently of the main thread, so it still fires when a frozen screen is caused by a blocking
 * main-thread call.
 *
 * Holds only timing state; the host owns the run itself and decides what a force-stop means.
 */
internal class RunWatchdog(
    private val scope: CoroutineScope,
    private val isRunActive: () -> Boolean,
    private val onOverdue: () -> Unit
) {

    private var job: Job? = null

    @Volatile
    private var runStartedAtMs = 0L

    @Volatile
    private var lastProgressAtMs = 0L

    /**
     * Starts (or restarts) the per-turn watchdog for a new turn: it aborts a turn that reached
     * [RunLifecyclePolicy.RUN_HARD_CAP_MS] or produced no progress for
     * [RunLifecyclePolicy.RUN_STALL_TIMEOUT_MS].
     */
    fun start() {
        stop()
        runStartedAtMs = System.currentTimeMillis()
        lastProgressAtMs = runStartedAtMs
        job = scope.launch {
            // Poll often enough to be responsive, cheap enough to be free.
            val tick = WATCHDOG_TICK_MS
            while (isActive) {
                delay(tick)
                if (!isRunActive()) return@launch
                val now = System.currentTimeMillis()
                if (RunLifecyclePolicy.isOverdue(runStartedAtMs, lastProgressAtMs, now)) {
                    onOverdue()
                    return@launch
                }
            }
        }
    }

    /** Stops the watchdog and clears the timing state (a turn ended normally). */
    fun stop() {
        job?.cancel()
        job = null
        runStartedAtMs = 0L
        lastProgressAtMs = 0L
    }

    /** Marks that the live turn is making progress, so the watchdog does not treat it as hung. */
    fun noteProgress() {
        lastProgressAtMs = System.currentTimeMillis()
    }

    /**
     * Wall-clock age of the tracked turn, for the honest force-stop log line. Returns `0` when no
     * turn is being tracked ([runStartedAtMs] `<= 0`); otherwise `System.currentTimeMillis()` would
     * be reported as the "elapsed" time, which is not a duration at all (that was the
     * `1790061945s` bug on the stale-state reset path, where the watchdog had never started).
     */
    fun elapsedMs(): Long {
        val startedAt = runStartedAtMs
        return if (startedAt <= 0L) 0L else System.currentTimeMillis() - startedAt
    }

    private companion object {
        // How often the per-turn watchdog re-checks the stall/hard-cap budgets.
        private const val WATCHDOG_TICK_MS = 15_000L
    }
}
