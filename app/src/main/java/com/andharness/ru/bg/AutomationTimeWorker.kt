package com.andharness.ru.bg

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andharness.ru.data.diagnostics.PowerGuardEntryPoint
import com.andharness.ru.domain.automation.AutomationEvent
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.common.OccurrenceGuard
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * Fires one `time` automation rule (update.md §11.6) and re-arms it for its next occurrence.
 *
 * A `time` trigger has no system broadcast, so it is evaluated on a per-rule one-time
 * [androidx.work.WorkManager] request that re-enqueues itself after running — the same
 * self-chaining pattern as [ScheduleWorker]. WorkManager is inexact by design: the rule fires
 * around, not exactly at, the configured minute.
 *
 * Dependencies come from the Hilt graph via an [EntryPoint] so no custom WorkerFactory is needed.
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
class AutomationTimeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ruleId = inputData.getLong(KEY_RULE_ID, -1L)
        if (ruleId <= 0L) return Result.success()
        val slot = inputData.getLong(KEY_SLOT_MS, -1L)
        if (slot <= 0L) return Result.success()

        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            AutomationTimeWorkerEntryPoint::class.java
        )
        val repository = entry.automationRuleRepository()
        val rule = repository.getById(ruleId) ?: return Result.success()
        if (!rule.enabled || rule.triggerType != AutomationSpec.TRIGGER_TIME) return Result.success()

        // WorkManager is at-least-once: a mid-run process death re-delivers this item. Skip a slot
        // that already ran (fast path), then atomically claim it so two concurrent deliveries cannot
        // both fire the same occurrence. A skipped delivery must NOT re-arm — the delivery that
        // actually ran already did.
        if (OccurrenceGuard.alreadyExecuted(rule.lastRunAt, slot)) return Result.success()

        // Power guard (update.md idea #19): a `time` rule is periodic automation, i.e. non-essential.
        // Under critical power this (already passed) occurrence is skipped honestly and the rule is
        // re-armed for its next occurrence; the slot is NOT claimed, so nothing looks like it ran.
        val deferReason = EntryPointAccessors
            .fromApplication(applicationContext, PowerGuardEntryPoint::class.java)
            .powerGuard()
            .deferReason()
        if (deferReason != null) {
            Log.i(TAG, "Automation time rule $ruleId deferred by the power guard: $deferReason")
            runCatching { entry.automationScheduler().schedule(rule) }
            return Result.success()
        }

        if (!repository.claimRun(ruleId, slot)) return Result.success()

        var ran = false
        try {
            entry.ruleEngine().handle(
                AutomationEvent(triggerType = AutomationSpec.TRIGGER_TIME, ruleId = ruleId)
            )
            // Keep the persisted marker at least at the claimed slot (never behind it), so a clock
            // skew cannot make the occurrence look un-run to a re-delivery.
            repository.markRun(ruleId, maxOf(System.currentTimeMillis(), slot))
            ran = true
        } catch (e: Exception) {
            Log.e(TAG, "Automation time rule $ruleId failed", e)
        }
        if (ran) {
            // Re-arm for the next occurrence only after this one completed successfully.
            val fresh = repository.getById(ruleId)
            if (fresh != null && fresh.enabled && fresh.triggerType == AutomationSpec.TRIGGER_TIME) {
                runCatching { entry.automationScheduler().schedule(fresh) }
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_RULE_ID = "automation_rule_id"

        /** The epoch-millis occurrence this work item is for (idempotency; see [OccurrenceGuard]). */
        const val KEY_SLOT_MS = "automation_slot_ms"
        private const val TAG = "Andharness"
    }
}
