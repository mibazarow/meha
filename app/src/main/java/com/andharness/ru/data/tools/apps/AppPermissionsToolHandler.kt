package com.andharness.ru.data.tools.apps

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `app_permissions`: lists a package's requested permissions and their grant state. */
internal class AppPermissionsToolHandler(
    private val context: Context,
    private val inspector: AppPackageInspector,
) : ToolHandler {

    override val name = "app_permissions"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val pkg = (arguments["package"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (pkg.isEmpty()) return@withContext ToolResult(false, "Укажите package.")
            val pm = context.packageManager
            val info = inspector.load(pm, pkg, PackageManager.GET_PERMISSIONS)
                ?: return@withContext ToolResult(
                    true,
                    "Приложение «$pkg» не найдено или недоступно (ограничения видимости пакетов)."
                )
            val requested = info.requestedPermissions
            if (requested.isNullOrEmpty()) {
                return@withContext ToolResult(
                    true,
                    "Приложение «$pkg» не запрашивает разрешений (или сведения недоступны)."
                )
            }
            val flags = info.requestedPermissionsFlags
            val lines = requested.mapIndexed { index, permission ->
                val granted = flags != null && index < flags.size &&
                    (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                "$permission — ${if (granted) "выдано" else "не выдано"}"
            }
            ToolResult(true, "Разрешения «$pkg» (${requested.size}):\n" + lines.joinToString("\n"))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
