package com.andharness.ru.data.tools.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `app_package_info`: reports versions, SDK levels, install/update time and installer. */
internal class AppPackageInfoToolHandler(
    private val context: Context,
    private val inspector: AppPackageInspector,
) : ToolHandler {

    override val name = "app_package_info"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val pkg = (arguments["package"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (pkg.isEmpty()) return@withContext ToolResult(false, "Укажите package.")
            val pm = context.packageManager
            val info = inspector.load(pm, pkg, 0)
                ?: return@withContext ToolResult(
                    true,
                    "Приложение «$pkg» не найдено или недоступно (ограничения видимости пакетов)."
                )
            val app = info.applicationInfo
            val isSystem = app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label = app?.let { pm.getApplicationLabel(it).toString() } ?: "?"
            ToolResult(
                true,
                buildString {
                    append("Пакет: $pkg\n")
                    append("Название: $label\n")
                    append("Версия: ${info.versionName ?: "?"} (versionCode=${inspector.versionCode(info)})\n")
                    append("targetSdk: ${app?.targetSdkVersion ?: "?"}, minSdk: ${app?.minSdkVersion ?: "?"}\n")
                    append("Установлено: ${inspector.time(info.firstInstallTime)}\n")
                    append("Обновлено: ${inspector.time(info.lastUpdateTime)}\n")
                    append("Системное: ${if (isSystem) "да" else "нет"}\n")
                    append("Установщик: ${inspector.installer(pm, pkg)}")
                }
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
