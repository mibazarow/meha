package com.andharness.ru.domain.communications

import com.andharness.ru.domain.diagnostics.PowerGuardPolicy
import com.andharness.ru.domain.diagnostics.PowerSnapshot

/**
 * What the background Communications worker should do with the pending recordings right now.
 *
 * - [RUN] — process up to the per-run bound within the remaining daily budget.
 * - [SKIP] — nothing to do at all: automation is off, or today's budget is already exhausted (the
 *   next calendar day resets it, so there is no point retrying within today).
 * - [DEFER] — a scheduling/power condition blocks the run for now, but it may proceed later: the
 *   device is not charging in `CHARGING` mode, or the power guard asked for a deferral. This is an
 *   honest no-op, never an error.
 */
enum class CommunicationProcessDecision {
    RUN,
    SKIP,
    DEFER,
}

/**
 * Pure gate that folds the configured [CommunicationsMode], the daily processing budget and the
 * device [PowerSnapshot] into one [CommunicationProcessDecision]. Kept Android-free so the whole
 * truth table is unit-testable on the JVM; the worker only reads the real configuration/power state
 * and calls [decide].
 *
 * Rules, evaluated in order:
 * 1. [CommunicationsMode.OFF] → [CommunicationProcessDecision.SKIP] (nothing may run);
 * 2. `budgetRemainingMs <= 0` → [CommunicationProcessDecision.SKIP] (today's budget is spent);
 * 3. an unreadable snapshot → [CommunicationProcessDecision.RUN] in [CommunicationsMode.ALWAYS]
 *    (refusing on unknown data would be a lie), but [CommunicationProcessDecision.DEFER] in
 *    [CommunicationsMode.CHARGING] (charging cannot be confirmed, so the promise is not kept);
 * 4. [CommunicationsMode.CHARGING] while not charging → [CommunicationProcessDecision.DEFER];
 * 5. the standard [PowerGuardPolicy] deferral (critical battery / severe thermal) →
 *    [CommunicationProcessDecision.DEFER];
 * 6. otherwise [CommunicationProcessDecision.RUN].
 *
 * Official references:
 * - Battery/charging snapshot: https://developer.android.com/reference/android/os/BatteryManager
 * - Thermal status: https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()
 */
object CommunicationProcessGate {

    fun decide(
        mode: CommunicationsMode,
        snapshot: PowerSnapshot,
        budgetRemainingMs: Long,
    ): CommunicationProcessDecision {
        if (mode == CommunicationsMode.OFF) return CommunicationProcessDecision.SKIP
        if (budgetRemainingMs <= 0L) return CommunicationProcessDecision.SKIP

        if (!snapshot.known) {
            return when (mode) {
                CommunicationsMode.ALWAYS -> CommunicationProcessDecision.RUN
                CommunicationsMode.CHARGING -> CommunicationProcessDecision.DEFER
                CommunicationsMode.OFF -> CommunicationProcessDecision.SKIP
            }
        }

        if (mode == CommunicationsMode.CHARGING && !snapshot.charging) {
            return CommunicationProcessDecision.DEFER
        }

        val verdict = PowerGuardPolicy.evaluate(snapshot, essential = false)
        return if (verdict.defer) CommunicationProcessDecision.DEFER
        else CommunicationProcessDecision.RUN
    }
}
