package com.andharness.ru.data.automation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.andharness.ru.bg.AutomationTimeWorker
import com.andharness.ru.domain.automation.AutomationRule
import com.andharness.ru.domain.common.OccurrenceGuard
import com.andharness.ru.domain.automation.AutomationScheduler
import com.andharness.ru.domain.automation.AutomationSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms each `time` rule as a unique one-time [AutomationTimeWorker] set to its next occurrence; the
 * worker re-enqueues itself (see [AutomationTimeWorker]). Non-time rules need no OS wake-up and are
 * cancelled here if one was ever armed.
 *
 * Honest limitation: WorkManager delays are inexact — the OS may run the worker later than the
 * requested minute (Doze, app standby, vendor policies).
 *
 * Reference: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started
 */
@Singleton
class AutomationSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AutomationScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun schedule(rule: AutomationRule) {
        if (!rule.enabled || rule.triggerType != AutomationSpec.TRIGGER_TIME) {
            cancel(rule.id)
            return
        }
        val time = AutomationSpec.parseTime(rule.triggerConfig)
        if (time == null) {
            cancel(rule.id)
            return
        }
        val now = System.currentTimeMillis()
        val delay = nextDelayMillis(time.first, time.second, now)
        // The exact occurrence this work item is for; carried in the persisted inputData so a
        // re-delivery after a process death can be recognised and skipped (WorkManager is
        // at-least-once). See [com.andharness.ru.domain.common.OccurrenceGuard].
        val slot = OccurrenceGuard.slotMillis(now, delay)
        val request = OneTimeWorkRequestBuilder<AutomationTimeWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    AutomationTimeWorker.KEY_RULE_ID to rule.id,
                    AutomationTimeWorker.KEY_SLOT_MS to slot
                )
            )
            .addTag(tag(rule.id))
            .build()
        workManager.enqueueUniqueWork(uniqueName(rule.id), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(ruleId: Long) {
        workManager.cancelUniqueWork(uniqueName(ruleId))
    }

    override fun rescheduleAll(rules: List<AutomationRule>) {
        rules.forEach { schedule(it) }
    }

    private fun nextDelayMillis(hour: Int, minute: Int, nowMillis: Long): Long {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var candidate = now.toLocalDate().atTime(hour, minute).atZone(zone)
        if (candidate.toInstant().toEpochMilli() <= nowMillis + 1_000L) {
            candidate = now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone)
        }
        return candidate.toInstant().toEpochMilli() - nowMillis
    }

    private fun uniqueName(ruleId: Long) = "andharness_automation_$ruleId"
    private fun tag(ruleId: Long) = "andharness_automation_tag_$ruleId"
}
