package com.andharness.ru.data.tools.apps

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.permission.SpecialAccess

/**
 * AppOps / usage-stats access shared by [app_ops_self] and [app_data_usage]: reading app-op modes
 * for this app's own uid and summing per-uid network traffic.
 *
 * References:
 * - `AppOpsManager.unsafeCheckOpNoThrow` / `checkOpNoThrow`, `OPSTR_GET_USAGE_STATS`:
 *   https://developer.android.com/reference/android/app/AppOpsManager
 * - `NetworkStatsManager.queryDetailsForUid`:
 *   https://developer.android.com/reference/android/app/usage/NetworkStatsManager
 */
internal class AppOpsAccess(private val context: Context) {

    /**
     * Reads the app-op mode for [op] on this app's own [uid]. Uses the API 29+
     * [AppOpsManager.unsafeCheckOpNoThrow] and falls back to the (deprecated) `checkOpNoThrow`
     * below Q. Returns `null` when the platform does not know the operation.
     */
    fun checkMode(appOps: AppOpsManager, op: String, uid: Int, pkg: String): Int? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(op, uid, pkg)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(op, uid, pkg)
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Human-readable [AppOpsManager] mode. */
    fun modeName(mode: Int): String = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "разрешено"
        AppOpsManager.MODE_IGNORED -> "запрещено (игнорируется)"
        AppOpsManager.MODE_ERRORED -> "запрещено (ошибка)"
        AppOpsManager.MODE_DEFAULT -> "по умолчанию (решает система)"
        else -> "неизвестный режим"
    }

    /**
     * `PACKAGE_USAGE_STATS` is a special access, not a runtime permission: it must be both declared
     * in the manifest and granted by the user in Settings. The grant is verified through
     * [AppOpsManager.OPSTR_GET_USAGE_STATS].
     */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessRequired(): ToolResult =
        ToolResult(false, SpecialAccess.required(SpecialAccess.USAGE_ACCESS))

    /**
     * Sums received/transmitted bytes for [uid] on one transport, or `null` when the platform
     * refuses the query (missing `PACKAGE_USAGE_STATS`, unknown transport). `networkType` uses the
     * `ConnectivityManager.TYPE_*` constants expected by [NetworkStatsManager].
     */
    fun uidTrafficOrNull(
        networkStatsManager: NetworkStatsManager,
        networkType: Int,
        start: Long,
        end: Long,
        uid: Int,
    ): Pair<Long, Long>? = try {
        @Suppress("MissingPermission")
        val stats = networkStatsManager.queryDetailsForUid(networkType, null, start, end, uid)
        try {
            var received = 0L
            var sent = 0L
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                received += bucket.rxBytes
                sent += bucket.txBytes
            }
            received to sent
        } finally {
            stats.close()
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
