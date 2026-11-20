package com.andharness.ru.data.tools.apps

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * `app_data_usage`: reports per-uid Wi-Fi + mobile traffic for a package over a day window.
 * `NetworkStatsManager.queryDetailsForUid` and the `PACKAGE_USAGE_STATS` special access (checked by
 * [AppOpsAccess.hasUsageAccess]):
 * https://developer.android.com/reference/android/app/usage/NetworkStatsManager
 */
internal class AppDataUsageToolHandler(
    private val context: Context,
    private val ops: AppOpsAccess,
    private val inspector: AppPackageInspector,
) : ToolHandler {

    override val name = "app_data_usage"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val pkg = (arguments["package"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (pkg.isEmpty()) return@withContext ToolResult(false, "Укажите package.")
            val days = ((arguments["days"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
                ?: AppToolLimits.DEFAULT_TRAFFIC_DAYS).coerceIn(1, AppToolLimits.MAX_TRAFFIC_DAYS)
            if (!ops.hasUsageAccess()) return@withContext ops.usageAccessRequired()
            val pm = context.packageManager
            val app = inspector.load(pm, pkg, 0)?.applicationInfo
                ?: return@withContext ToolResult(
                    true,
                    "Приложение «$pkg» не найдено или недоступно (ограничения видимости пакетов).",
                )
            val uid = app.uid
            val networkStatsManager = context.getSystemService(NetworkStatsManager::class.java)
                ?: return@withContext ToolResult(false, "NetworkStatsManager недоступен на устройстве.")
            val end = System.currentTimeMillis()
            val start = end - TimeUnit.DAYS.toMillis(days.toLong())
            val wifi = ops.uidTrafficOrNull(
                networkStatsManager,
                ConnectivityManager.TYPE_WIFI,
                start,
                end,
                uid,
            )
            val mobile = ops.uidTrafficOrNull(
                networkStatsManager,
                ConnectivityManager.TYPE_MOBILE,
                start,
                end,
                uid,
            )
            if (wifi == null && mobile == null) {
                return@withContext ToolResult(
                    true,
                    "За $days дн. данных о трафике приложения «$pkg» нет " +
                        "(система не вернула статистику по Wi-Fi и мобильной сети).",
                )
            }
            val received = (wifi?.first ?: 0L) + (mobile?.first ?: 0L)
            val sent = (wifi?.second ?: 0L) + (mobile?.second ?: 0L)
            val label = try {
                pm.getApplicationLabel(app).toString()
            } catch (_: Exception) {
                pkg
            }
            ToolResult(
                true,
                buildString {
                    appendLine("Трафик «$label» ($pkg, uid=$uid) за $days дн.:")
                    appendLine("Принято: ${formatBytes(received)}")
                    appendLine("Отправлено: ${formatBytes(sent)}")
                    append("Всего: ${formatBytes(received + sent)}")
                }
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
