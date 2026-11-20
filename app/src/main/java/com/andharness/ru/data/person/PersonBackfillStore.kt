package com.andharness.ru.data.person

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted cursors of the one-shot personal-base backfill (update.md §4.4), so a bounded run can
 * resume where the previous one stopped instead of re-reading the whole history.
 *
 * - SMS/call-log cursors are the last processed provider `_id` (ascending scan);
 * - [contactsDone] is a one-shot flag: contacts are small and indexed in a single pass;
 * - [runs] bounds the total number of runs of an unbounded source (no infinite worker chain).
 */
@Singleton
class PersonBackfillStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(MessagingIndexConfigStore.PREFS_FILE, Context.MODE_PRIVATE)

    fun smsCursor(): Long = prefs.getLong(KEY_SMS_CURSOR, 0L)

    fun setSmsCursor(id: Long) {
        prefs.edit().putLong(KEY_SMS_CURSOR, id).apply()
    }

    fun callsCursor(): Long = prefs.getLong(KEY_CALLS_CURSOR, 0L)

    fun setCallsCursor(id: Long) {
        prefs.edit().putLong(KEY_CALLS_CURSOR, id).apply()
    }

    fun contactsDone(): Boolean = prefs.getBoolean(KEY_CONTACTS_DONE, false)

    fun setContactsDone(done: Boolean) {
        prefs.edit().putBoolean(KEY_CONTACTS_DONE, done).apply()
    }

    fun runs(): Int = prefs.getInt(KEY_RUNS, 0)

    fun bumpRuns(): Int {
        val next = runs() + 1
        prefs.edit().putInt(KEY_RUNS, next).apply()
        return next
    }

    /** Clears every cursor so the next backfill starts from scratch (purge / re-enable). */
    fun reset() {
        prefs.edit()
            .remove(KEY_SMS_CURSOR)
            .remove(KEY_CALLS_CURSOR)
            .remove(KEY_CONTACTS_DONE)
            .remove(KEY_RUNS)
            .apply()
    }

    companion object {
        private const val KEY_SMS_CURSOR = "backfill_sms_cursor"
        private const val KEY_CALLS_CURSOR = "backfill_calls_cursor"
        private const val KEY_CONTACTS_DONE = "backfill_contacts_done"
        private const val KEY_RUNS = "backfill_runs"
    }
}
