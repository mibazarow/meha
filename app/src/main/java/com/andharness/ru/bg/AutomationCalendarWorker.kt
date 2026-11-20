package com.andharness.ru.bg

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andharness.ru.data.util.columnIndex
import com.andharness.ru.data.util.useRows
import com.andharness.ru.domain.automation.AutomationEvent
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.automation.TriggerConfigs
import dagger.hilt.android.EntryPointAccessors

/**
 * Periodic check behind the `calendar_event` automation trigger (update.md idea #18).
 *
 * A rule fires from `minutes_before` up to a grace period after the event start; the event start is
 * used as the idempotency slot so the same occurrence never fires twice (WorkManager is
 * at-least-once). It queries [CalendarContract.Events] directly, so it needs READ_CALENDAR — without
 * it the worker does nothing and the rule-create tool reports the missing access honestly.
 *
 * WorkManager is inexact by design, so a reminder can be a few minutes late; the trailing grace in
 * [TriggerConfigs.CalendarSpec.isDue] keeps a late check from missing the event entirely.
 *
 * References:
 * - Calendar provider (Events table, DTSTART):
 *   https://developer.android.com/identity/providers/calendar-provider
 * - `CalendarContract.Events`: https://developer.android.com/reference/android/provider/CalendarContract.Events
 */
class AutomationCalendarWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            AutomationTimeWorkerEntryPoint::class.java
        )
        val repository = entry.automationRuleRepository()
        val rules = runCatching { repository.getAll() }.getOrElse { return Result.success() }
            .filter { it.enabled && it.triggerType == AutomationSpec.TRIGGER_CALENDAR_EVENT }
        if (rules.isEmpty()) return Result.success()

        if (applicationContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "calendar_event rules exist but READ_CALENDAR is not granted — skipping")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val from = now - TriggerConfigs.CALENDAR_GRACE_MS
        val to = now + HORIZON_MS
        val events = runCatching { queryEvents(from, to) }.getOrElse {
            Log.e(TAG, "Calendar query failed", it)
            return Result.success()
        }

        events.forEach { event ->
            val start = event.second
            rules.forEach { rule ->
                val spec = TriggerConfigs.parseCalendar(rule.triggerConfig) ?: return@forEach
                if (!spec.matchesTitle(event.first)) return@forEach
                if (!spec.isDue(start, now)) return@forEach
                // The event start is the occurrence identity: one firing per event per rule.
                if (!repository.claimRun(rule.id, start)) return@forEach
                runCatching {
                    entry.ruleEngine().handle(
                        AutomationEvent(
                            triggerType = AutomationSpec.TRIGGER_CALENDAR_EVENT,
                            ruleId = rule.id,
                            value = event.first
                        )
                    )
                    repository.markRun(rule.id, maxOf(System.currentTimeMillis(), start))
                }.onFailure { Log.e(TAG, "Calendar rule ${rule.id} failed", it) }
            }
        }
        return Result.success()
    }

    /** Returns `(title, DTSTART)` for non-deleted events starting inside the window. */
    private fun queryEvents(from: Long, to: Long): List<Pair<String?, Long>> {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
            "${CalendarContract.Events.DTSTART} <= ?"
        val args = arrayOf(from.toString(), to.toString())
        val out = ArrayList<Pair<String?, Long>>()
        applicationContext.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            args,
            CalendarContract.Events.DTSTART
        )?.useRows { cursor ->
            val startIdx = cursor.columnIndex(CalendarContract.Events.DTSTART)
            if (startIdx < 0) return@useRows
            val start = cursor.getLong(startIdx)
            if (start <= 0L) return@useRows
            val titleIdx = cursor.columnIndex(CalendarContract.Events.TITLE)
            out += (if (titleIdx >= 0) cursor.getString(titleIdx) else null) to start
        }
        return out
    }

    companion object {
        private const val TAG = "Andharness"

        /** How far ahead events are looked up — comfortably above the periodic check interval. */
        private const val HORIZON_MS = 60 * 60_000L

        const val UNIQUE_NAME = "andharness_automation_calendar_check"
    }
}
