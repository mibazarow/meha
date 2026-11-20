package com.andharness.ru.data.tools.roles

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.domain.ui.requestBoundedUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Builds one [ToolHandler] per role tool: [role_request], [role_hold].
 *
 * Both are honest about platform limits: role management only exists on API 29+ (`RoleManager`),
 * and a role request is a user-facing system dialog whose real outcome is reported (never faked).
 *
 * References:
 * - `RoleManager.isRoleAvailable(String)`, `RoleManager.isRoleHeld(String)`,
 *   `RoleManager.createRequestRoleIntent(String)` (API 29+):
 *   https://developer.android.com/reference/android/app/role/RoleManager
 * - Starting an interactive flow from an Activity and returning its raw result:
 *   https://developer.android.com/training/basics/intents/result
 */
class RoleToolsHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
) {

    /** `SMS` -> `android.app.role.SMS`; a fully-qualified name is left untouched. */
    private fun normalizeRole(raw: String): String =
        if (raw.contains('.')) raw else "android.app.role.${raw.uppercase(Locale.US)}"

    /** Shared guard: returns the `RoleManager`, or a terminal result to report honestly. */
    private fun roleManagerOrNull(): RoleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
        } else {
            null
        }

    private fun unavailableMessage(): String =
        "Управление ролями недоступно: RoleManager появился в Android 10 (API 29)."

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "role_request"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val raw = (arguments["role"] as? JsonPrimitive)?.content?.trim().orEmpty()
                requestRole(raw)
            }
        },
        object : ToolHandler {
            override val name = "call_screening_role_request"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                requestRole(
                    "CALL_SCREENING",
                    " Учитывайте: назначенный CallScreeningService обязан ответить на входящий вызов в " +
                        "течение 5 секунд, иначе платформа отклоняет вызов.",
                )
            }
        },
        object : ToolHandler {
            override val name = "dialer_role_request"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                requestRole(
                    "DIALER",
                    " Роль телефона по умолчанию подразумевает InCallService: приложение должно показывать " +
                        "экран вызова и корректно обрабатывать входящие и исходящие звонки.",
                )
            }
        },
        object : ToolHandler {
            override val name = "role_hold"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val raw = (arguments["role"] as? JsonPrimitive)?.content?.trim().orEmpty()
                    if (raw.isEmpty()) {
                        return@withContext ToolResult(
                            false,
                            "Укажите role, например SMS, DIALER, BROWSER, HOME.",
                        )
                    }
                    val roleManager = roleManagerOrNull()
                        ?: return@withContext ToolResult(true, unavailableMessage())
                    val role = normalizeRole(raw)
                    if (!roleManager.isRoleAvailable(role)) {
                        return@withContext ToolResult(
                            true,
                            "Роль «$role» недоступна на этом устройстве.",
                        )
                    }
                    val held = roleManager.isRoleHeld(role)
                    if (held) {
                        ToolResult(true, "Роль $role выдана приложению ${context.packageName}.")
                    } else {
                        ToolResult(
                            true,
                            "Роль $role НЕ выдана приложению ${context.packageName}.",
                        )
                    }
                } catch (e: SecurityException) {
                    ToolResult(false, "Нет прав на проверку роли: ${e.message}")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    /** Shared role-request flow: resolves the role, asks the UI bridge and reports the real outcome. */
    private suspend fun requestRole(raw: String, note: String = ""): ToolResult {
        return try {
            if (raw.isEmpty()) {
                return ToolResult(
                    false,
                    "Укажите роль, например SMS, DIALER, BROWSER, HOME, CALL_SCREENING.",
                )
            }
            val roleManager = roleManagerOrNull() ?: return ToolResult(true, unavailableMessage())
            val role = normalizeRole(raw)
            if (!roleManager.isRoleAvailable(role)) {
                return ToolResult(true, "Роль «$role» недоступна на этом устройстве.")
            }
            if (roleManager.isRoleHeld(role)) {
                return ToolResult(
                    true,
                    "Роль $role уже выдана этому приложению (${context.packageName}).$note",
                )
            }
            val intent = roleManager.createRequestRoleIntent(role)
            // Bounded by the shared UI-consent policy (default UiConsentPolicy.TIMEOUT_MS) so a
            // broadcast caller never holds its goAsync() pending result past the ANR window.
            val bridgeResult = uiBridge.requestBoundedUi(
                UiRequest.RoleRequest(role, intent),
            )
            when (bridgeResult) {
                is UiResult.ActivityResult -> {
                    val granted = bridgeResult.resultCode == Activity.RESULT_OK &&
                        roleManager.isRoleHeld(role)
                    when {
                        granted -> ToolResult(
                            true,
                            "Роль $role выдана приложению ${context.packageName}.$note",
                        )
                        bridgeResult.resultCode == Activity.RESULT_OK -> ToolResult(
                            false,
                            "Система вернула «ОК», но роль $role приложению не выдана.",
                        )
                        else -> ToolResult(false, "Пользователь отклонил выдачу роли $role.")
                    }
                }
                UiResult.Cancelled -> ToolResult(
                    false,
                    "Запрос роли $role отменён пользователем (диалог закрыт).",
                )
                is UiResult.Failed -> ToolResult(false, bridgeResult.message)
                else -> ToolResult(false, "Неожиданный ответ интерфейса при запросе роли $role.")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет прав на запрос роли: ${e.message}")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
