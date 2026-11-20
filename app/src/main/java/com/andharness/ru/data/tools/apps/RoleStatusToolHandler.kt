package com.andharness.ru.data.tools.apps

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * `role_status`: reports whether the app itself holds a system role (API 29+).
 * `RoleManager.isRoleAvailable` / `isRoleHeld`:
 * https://developer.android.com/reference/android/app/role/RoleManager
 */
internal class RoleStatusToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "role_status"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val raw = (arguments["role"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (raw.isEmpty()) {
                return@withContext ToolResult(false, "Укажите role, например SMS, DIALER, BROWSER, HOME.")
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@withContext ToolResult(
                    true,
                    "Проверка ролей недоступна: RoleManager появился в Android 10 (API 29)."
                )
            }
            val roleManager = context.getSystemService(RoleManager::class.java)
                ?: return@withContext ToolResult(true, "RoleManager недоступен на этом устройстве.")
            val role = if (raw.contains('.')) raw else "android.app.role.${raw.uppercase(Locale.US)}"
            if (!roleManager.isRoleAvailable(role)) {
                return@withContext ToolResult(true, "Роль «$role» недоступна на этом устройстве.")
            }
            val held = roleManager.isRoleHeld(role)
            val own = if (held) {
                "роль выдана этому приложению (${context.packageName})"
            } else {
                "роль не выдана этому приложению"
            }
            val note = if (held) "" else " Владельца роли можно узнать только в системных настройках."
            ToolResult(true, "Роль $role: доступна; $own.$note")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
