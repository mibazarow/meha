package com.andharness.ru.data.tools.usage

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * Builds the [app_usage] tool: screen-time / most-used apps for the last N days via UsageStatsManager.
 * Requires the special "Usage access" permission (PACKAGE_USAGE_STATS), granted by the user in Settings.
 *
 * References (official Android docs):
 * - `UsageStatsManager.queryUsageStats` / `UsageStats.getTotalTimeInForeground`:
 *   https://developer.android.com/reference/android/app/usage/UsageStatsManager
 * - Usage access via `AppOpsManager.OPSTR_GET_USAGE_STATS`:
 *   https://developer.android.com/reference/android/app/AppOpsManager#OPSTR_GET_USAGE_STATS
 */
class UsageToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "app_usage"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    if (!hasUsageAccess()) {
                        return@withContext ToolResult(
                            false,
                            com.andharness.ru.domain.permission.SpecialAccess.required(
                                com.andharness.ru.domain.permission.SpecialAccess.USAGE_ACCESS
                            )
                        )
                    }
                    val days = (arguments["days"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(1, 30) ?: 1
                    val end = System.currentTimeMillis()
                    val start = end - TimeUnit.DAYS.toMillis(days.toLong())
                    val usm = context.getSystemService(UsageStatsManager::class.java)
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                        ?: return@withContext ToolResult(false, "Нет данных статистики.")
                    val totals = HashMap<String, Long>()
                    for (s in stats) {
                        totals[s.packageName] = (totals[s.packageName] ?: 0L) + s.totalTimeInForeground
                    }
                    val pm = context.packageManager
                    val lines = totals.entries
                        .filter { it.value > 0 }
                        .sortedByDescending { it.value }
                        .take(20)
                        .map { (pkg, ms) ->
                            val label = try {
                                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                            } catch (e: Exception) {
                                pkg
                            }
                            val minutes = ms / 60_000
                            "$label ($pkg) — $minutes мин"
                        }
                    if (lines.isEmpty()) ToolResult(true, "Данных об использовании за $days дн. нет.")
                    else ToolResult(true, "Использование за $days дн.:\n" + lines.joinToString("\n"))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
