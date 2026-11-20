package com.andharness.ru.data.tools.apps

import android.content.Context
import android.content.pm.PackageManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `app_activities`: lists a package's declared activities, services and receivers. */
internal class AppActivitiesToolHandler(
    private val context: Context,
    private val inspector: AppPackageInspector,
) : ToolHandler {

    override val name = "app_activities"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val pkg = (arguments["package"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (pkg.isEmpty()) return@withContext ToolResult(false, "Укажите package.")
            val pm = context.packageManager
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS
            val info = inspector.load(pm, pkg, flags)
                ?: return@withContext ToolResult(
                    true,
                    "Приложение «$pkg» не найдено или недоступно (ограничения видимости пакетов)."
                )
            ToolResult(
                true,
                buildString {
                    append("Компоненты «$pkg»:\n")
                    append(inspector.components("Активности", info.activities?.map { it.name }))
                    append("\n")
                    append(inspector.components("Сервисы", info.services?.map { it.name }))
                    append("\n")
                    append(inspector.components("Ресиверы", info.receivers?.map { it.name }))
                }
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
