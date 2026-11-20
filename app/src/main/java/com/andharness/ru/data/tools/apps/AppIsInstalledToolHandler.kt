package com.andharness.ru.data.tools.apps

import android.content.Context
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `app_is_installed`: reports whether a package is installed (and its version/enabled state). */
internal class AppIsInstalledToolHandler(
    private val context: Context,
    private val inspector: AppPackageInspector,
) : ToolHandler {

    override val name = "app_is_installed"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val pkg = (arguments["package"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (pkg.isEmpty()) return@withContext ToolResult(false, "Укажите package.")
            val pm = context.packageManager
            val info = inspector.load(pm, pkg, 0)
                ?: return@withContext ToolResult(
                    true,
                    "Приложение «$pkg» не установлено (или недоступно из-за ограничений видимости пакетов)."
                )
            val app = info.applicationInfo
            val enabled = app?.enabled ?: true
            val label = try {
                app?.let { pm.getApplicationLabel(it).toString() } ?: "?"
            } catch (_: Exception) {
                "?"
            }
            ToolResult(
                true,
                buildString {
                    appendLine("Приложение «$pkg» установлено: да.")
                    appendLine("Название: $label")
                    appendLine("Версия: ${info.versionName ?: "?"} (versionCode=${inspector.versionCode(info)})")
                    append("Включено: ${if (enabled) "да" else "нет"}")
                }
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
