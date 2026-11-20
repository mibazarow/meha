package com.andharness.ru.data.tools.apps

import android.content.Context
import android.content.pm.PackageManager
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `app_receivers`: lists a package's declared broadcast receivers (name + exported flag, plus the
 * manifest process when it sets one). Read-only via `PackageManager`; no permission beyond package
 * visibility is needed.
 *
 * References:
 * - `PackageManager.GET_RECEIVERS` populates `PackageInfo.receivers`:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#GET_RECEIVERS
 * - `PackageInfo.receivers` (`ActivityInfo[]`) and `ComponentInfo.exported` / `processName`:
 *   https://developer.android.com/reference/android/content/pm/PackageInfo
 *   https://developer.android.com/reference/android/content/pm/ActivityInfo
 */
internal class AppReceiversToolHandler(
    private val context: Context,
    private val inspector: AppPackageInspector,
) : ToolHandler {

    override val name = "app_receivers"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val pkg = arguments.str("package")
                ?: return@withContext ToolResult(false, "Укажите package.")
            val info = inspector.load(context.packageManager, pkg, PackageManager.GET_RECEIVERS)
                ?: return@withContext notFound(pkg)
            val components = info.receivers.orEmpty().map {
                DeclaredComponent(it.name, it.exported, it.processName)
            }
            ToolResult(true, AppComponentsSummary.format("Ресиверы «$pkg»", components))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    private fun notFound(pkg: String): ToolResult =
        ToolResult(true, "Приложение «$pkg» не найдено или недоступно (ограничения видимости пакетов).")
}
