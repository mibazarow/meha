package com.andharness.ru.data.tools.apps

import android.content.Context
import android.content.pm.PackageManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `app_list`: lists installed applications (package + label), capped at 100 entries. */
internal class AppListToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "app_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val lines = ArrayList<String>()
            for (appInfo in apps) {
                if (lines.size >= 100) break
                val label = pm.getApplicationLabel(appInfo).toString()
                lines.add("${appInfo.packageName} | $label")
            }
            if (lines.isEmpty()) {
                ToolResult(true, "Установленных приложений не найдено.")
            } else {
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
