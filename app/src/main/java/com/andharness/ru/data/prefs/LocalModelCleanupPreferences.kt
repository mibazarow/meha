package com.andharness.ru.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot flag for reclaiming the ORPHANED files of the removed on-device local chat model.
 *
 * Android preserves app data across an in-place update, so the old feature's downloads and KV slots
 * linger on devices that had it. The new build cleans them once; this flag records that the attempt
 * already ran, so it does not repeat on every launch.
 *
 * Plain `SharedPreferences` (file [PREFS_FILE]) — non-secret boolean bookkeeping, mirroring the
 * project's other one-shot-flag stores (e.g. `PersonBackfillStore.contactsDone`). Dedicated file so
 * it never mixes with user settings.
 * https://developer.android.com/reference/android/content/SharedPreferences
 */
@Singleton
class LocalModelCleanupPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** True once the one-time orphan cleanup has been attempted (success or failure). */
    fun isCleanupDone(): Boolean = prefs.getBoolean(KEY_CLEANUP_DONE, false)

    /** Records that the cleanup was attempted, so it runs at most once. */
    fun setCleanupDone() {
        prefs.edit().putBoolean(KEY_CLEANUP_DONE, true).apply()
    }

    companion object {
        const val PREFS_FILE = "andharness_local_model_cleanup"
        private const val KEY_CLEANUP_DONE = "orphan_cleanup_done"
    }
}
