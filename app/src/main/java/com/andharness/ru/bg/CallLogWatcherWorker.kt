package com.andharness.ru.bg

import android.content.Context
import android.provider.CallLog
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andharness.ru.data.intercept.ContactLookup
import com.andharness.ru.data.intercept.NumberChatManager
import com.andharness.ru.data.intercept.interceptEntryPoint
import com.andharness.ru.data.osint.NumberNormalizer
import com.andharness.ru.data.util.columnIndex
import com.andharness.ru.data.util.getStringOrNull
import com.andharness.ru.data.util.useRowsWhile

/**
 * Delayed fallback for call interception (update.md §2.2, "фолбэк без роли").
 *
 * When the app does NOT hold `ROLE_CALL_SCREENING` the system never calls the
 * `CallScreeningService`, so this periodic worker (every 15 min, only while interception is enabled)
 * scans the recent call log (`READ_CALL_LOG`) for unknown numbers and runs the SAME post-processing
 * (OSINT → number chat → notification).
 *
 * This is **honestly delayed** (up to ~15 min, and WorkManager may defer it further under Doze):
 * it is a safety net, not real-time interception. Only calls newer than the persisted cursor are
 * examined, duplicates are suppressed, and each run is bounded to [MAX_INCOMING_PER_RUN] numbers.
 *
 * References:
 * - `CallLog.Calls` (`NUMBER`, `DATE`, `CONTENT_URI`):
 *   https://developer.android.com/reference/android/provider/CallLog.Calls
 * - `Manifest.permission.READ_CALL_LOG`:
 *   https://developer.android.com/reference/android/Manifest.permission#READ_CALL_LOG
 * - `CoroutineWorker`: https://developer.android.com/reference/androidx/work/CoroutineWorker
 */
class CallLogWatcherWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = try {
            interceptEntryPoint(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Hilt entry point unavailable", e)
            return Result.success()
        }
        val manager = entry.numberChatManager()
        val store = entry.numberChatStore()

        if (!manager.isEnabled()) {
            manager.cancelFallbackWatcher()
            return Result.success()
        }

        val since = store.lastScanAt()
        val now = System.currentTimeMillis()

        // Call-back reminder (checklist §1): a MISSED call from a KNOWN contact, when the flag is on.
        // CallScreeningService never learns about an unanswered call, so the call log is the only
        // honest signal; this runs whenever the fallback watcher runs. Off by default (enabled=false
        // or callBackReminder=false is a strict no-op).
        val rules = try {
            entry.callRulesStore().current()
        } catch (e: Exception) {
            Log.w(TAG, "call rules unavailable; skipping call-back reminders", e)
            null
        }
        if (rules != null && rules.enabled && rules.callBackReminder) {
            createMissedCallReminders(entry, since, now)
        }

        val numbers = recentCallers(applicationContext, since)

        var processed = 0
        for (raw in numbers) {
            if (processed >= MAX_INCOMING_PER_RUN) break
            if (ContactLookup.isKnown(applicationContext, raw)) continue
            val normalized = NumberNormalizer.normalize(raw) ?: continue
            val tracked = store.get(normalized)
            if (tracked != null && now - tracked.lastEventAt < DEDUPE_WINDOW_MS) continue
            try {
                val outcome = manager.process(
                    rawNumber = normalized,
                    eventKind = NumberChatManager.EVENT_CALL_FALLBACK,
                )
                Log.i(TAG, "call-log fallback: ${outcome.note}")
                processed++
            } catch (e: Exception) {
                Log.e(TAG, "call-log fallback processing failed for $normalized", e)
            }
        }

        store.setLastScanAt(now)
        Log.i(TAG, "call-log fallback scan: ${numbers.size} recent number(s), $processed processed")
        return Result.success()
    }

    /**
     * Creates one one-shot call-back reminder per recently MISSED call from a **known contact**,
     * bounded to [MAX_REMINDERS_PER_RUN]. Called only when the rules are enabled and the flag is on.
     */
    private suspend fun createMissedCallReminders(
        entry: com.andharness.ru.data.intercept.InterceptEntryPoint,
        sinceMs: Long,
        now: Long,
    ) {
        val applier = entry.callSmsRuleApplier()
        var created = 0
        for (raw in recentMissedCallers(applicationContext, sinceMs)) {
            if (created >= MAX_REMINDERS_PER_RUN) break
            val name = ContactLookup.displayName(applicationContext, raw) ?: continue
            try {
                val outcome = applier.maybeCreateCallBackReminder(
                    rawNumber = raw,
                    contactName = name,
                    rejectedOrMissed = true,
                    nowMs = now,
                )
                if (outcome.acted) {
                    created++
                    Log.i(TAG, "call-log call-back reminder: ${outcome.note}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "call-back reminder creation failed for $raw", e)
            }
        }
    }

    /** Distinct caller numbers of MISSED calls newer than [sinceMs], newest first, bounded. */
    private fun recentMissedCallers(context: Context, sinceMs: Long): List<String> {
        val out = LinkedHashSet<String>()
        return try {
            val selection = "${CallLog.Calls.TYPE} = ?" +
                (if (sinceMs > 0) " AND ${CallLog.Calls.DATE} > ?" else "")
            val args = if (sinceMs > 0) {
                arrayOf(CallLog.Calls.MISSED_TYPE.toString(), sinceMs.toString())
            } else {
                arrayOf(CallLog.Calls.MISSED_TYPE.toString())
            }
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                selection,
                args,
                "${CallLog.Calls.DATE} DESC",
            )?.useRowsWhile { cursor ->
                if (out.size >= MAX_SCAN) return@useRowsWhile false
                val number = cursor.getStringOrNull(cursor.columnIndex(CallLog.Calls.NUMBER))
                if (!number.isNullOrBlank()) out.add(number)
                true
            }
            out.toList()
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG missing; missed-call reminders skipped (honest no-op)", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "missed-call query failed", e)
            emptyList()
        }
    }

    /** Distinct caller numbers newer than [sinceMs], newest first, bounded to [MAX_SCAN]. */
    private fun recentCallers(context: Context, sinceMs: Long): List<String> {
        val out = LinkedHashSet<String>()
        return try {
            val selection = if (sinceMs > 0) "${CallLog.Calls.DATE} > ?" else null
            val args = if (sinceMs > 0) arrayOf(sinceMs.toString()) else null
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                selection,
                args,
                "${CallLog.Calls.DATE} DESC",
            )?.useRowsWhile { cursor ->
                if (out.size >= MAX_SCAN) return@useRowsWhile false
                val number = cursor.getStringOrNull(cursor.columnIndex(CallLog.Calls.NUMBER))
                if (!number.isNullOrBlank()) out.add(number)
                true
            }
            out.toList()
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG missing; fallback scan skipped (honest no-op)", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "call-log query failed", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "Andharness"

        /** Unique periodic work name; the manager enqueues/cancels exactly this name. */
        const val WORK_NAME = "call_log_fallback_watcher"

        private const val MAX_SCAN = 25
        private const val MAX_INCOMING_PER_RUN = 10
        private const val MAX_REMINDERS_PER_RUN = 5
        private const val DEDUPE_WINDOW_MS = 6 * 60 * 60 * 1000L
    }
}
