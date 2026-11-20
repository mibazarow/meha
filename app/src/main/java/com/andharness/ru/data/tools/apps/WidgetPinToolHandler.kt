package com.andharness.ru.data.tools.apps

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Process
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `widget_pin`: offers this app's own widget to the launcher's pin dialog.
 * `AppWidgetManager.isRequestPinAppWidgetSupported()` + `requestPinAppWidget(ComponentName,
 * Bundle, PendingIntent)` / `getInstalledProvidersForPackage(String, UserHandle)` (only this app's
 * own widgets can be pinned, and only if the launcher supports it):
 * https://developer.android.com/develop/ui/views/appwidgets/discoverability
 */
internal class WidgetPinToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "widget_pin"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
                ?: return@withContext ToolResult(false, "AppWidgetManager недоступен на устройстве.")
            if (!appWidgetManager.isRequestPinAppWidgetSupported) {
                return@withContext ToolResult(
                    true,
                    "Лаунчер устройства не поддерживает закрепление виджетов " +
                        "(isRequestPinAppWidgetSupported = false) — предложить виджет нельзя.",
                )
            }
            val providers = appWidgetManager.getInstalledProvidersForPackage(
                context.packageName,
                Process.myUserHandle(),
            )
            if (providers.isEmpty()) {
                return@withContext ToolResult(
                    true,
                    "У приложения нет собственных виджетов (ни один AppWidgetProvider не объявлен), " +
                        "поэтому закреплять нечего.",
                )
            }
            val provider = providers.first()
            val label = try {
                provider.loadLabel(context.packageManager).toString()
            } catch (_: Exception) {
                provider.provider.flattenToShortString()
            }
            val shown = appWidgetManager.requestPinAppWidget(provider.provider, null, null)
            if (!shown) {
                return@withContext ToolResult(
                    false,
                    "Системе не удалось показать диалог закрепления виджета «$label».",
                )
            }
            val note = if (providers.size > 1) {
                " (у приложения ${providers.size} виджет(ов); предложен первый — «$label»)"
            } else {
                ""
            }
            ToolResult(
                true,
                "Системный диалог закрепления виджета «$label» показан$note. " +
                    "Решение о закреплении принимает пользователь; результат зависит от лаунчера.",
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
