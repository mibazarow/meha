package com.andharness.ru.data.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.andharness.ru.domain.diagnostics.ExitReasonCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the app's own `ApplicationExitInfo` history (update.md idea #19).
 *
 * `ActivityManager.getHistoricalProcessExitReasons` requires API 30 (Android 11) and needs no
 * permission; on older versions [supported] is false and [recent] returns an empty list instead of
 * pretending there is no history.
 *
 * Official references:
 * - https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int)
 * - https://developer.android.com/reference/android/app/ApplicationExitInfo
 */
@Singleton
class ProcessExitReader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** True when the platform can answer exit-history queries at all. */
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Up to [limit] most recent exits of this app, newest first. Never throws: a failure is logged
     * and reported as an empty list, so callers stay honest instead of inventing data.
     */
    fun recent(limit: Int): List<ProcessExitInfo> {
        if (!supported) return emptyList()
        val requested = limit.coerceIn(1, MAX_LIMIT)
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            // pid = 0 means "all processes of this package".
            am.getHistoricalProcessExitReasons(context.packageName, 0, requested)
                ?.take(requested)
                ?.map { info ->
                    ProcessExitInfo(
                        timestampMs = info.timestamp,
                        reasonCode = info.reason,
                        reasonLabel = ExitReasonCatalog.label(info.reason),
                        description = info.description?.takeIf { it.isNotBlank() },
                        importance = info.importance,
                        pssKb = info.pss.toLong(),
                        rssKb = info.rss.toLong(),
                        processName = info.processName
                    )
                }
                .orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Reading historical process exit reasons failed", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "Andharness"

        /** The platform keeps a bounded history; asking for more than this is pointless. */
        const val MAX_LIMIT = 64
    }
}
