package com.andharness.ru.data.tools.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.permission.DeviceAdminDecision
import com.andharness.ru.domain.permission.DeviceAdminEnableResult
import com.andharness.ru.domain.permission.DeviceAdminUiOutcome
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.domain.ui.requestBoundedUi
import com.andharness.ru.service.AndharnessDeviceAdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Builds the device-admin tools: [device_lock] (lock the screen) and [device_admin_enable]
 * (open the activation screen). Requires the user to activate ANDharness as a device admin.
 *
 * `device_admin_enable` asks the chat Activity to launch the system screen via
 * [UiInteractionBridge] (an Activity-scoped `ActivityResultLauncher`), then verifies the real state
 * with [DevicePolicyManager.isAdminActive] and reports honestly — it never claims success merely
 * because an intent was dispatched (BUG-021).
 *
 * References:
 * - `DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN` / `EXTRA_DEVICE_ADMIN` / `isAdminActive`:
 *   https://developer.android.com/reference/android/app/admin/DevicePolicyManager
 * - Device administration guide (launch from an Activity, confirm with `isAdminActive`):
 *   https://developer.android.com/guide/topics/admin/device-admin
 * - Activity Result APIs: https://developer.android.com/training/basics/intents/result
 */
class DeviceAdminToolsHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
) {

    /**
     * The admin receiver component. Deliberately NOT named `component`: inside an
     * `Intent(...).apply { ... }` block an unqualified `component` resolves to
     * `Intent.getComponent()` (a Java getter Kotlin exposes as a `component` property on `Intent`),
     * which silently put `null` into `EXTRA_DEVICE_ADMIN` and made Samsung's `SecDeviceAdminAdd`
     * log "No component specified in android.app.action.ADD_DEVICE_ADMIN" (BUG-021). The longer
     * name cannot be shadowed by any `Intent` member.
     */
    private val adminComponent get() = ComponentName(context, AndharnessDeviceAdminReceiver::class.java)
    private val dpm get() = context.getSystemService(DevicePolicyManager::class.java)

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "device_lock"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    if (dpm.isAdminActive(adminComponent)) {
                        dpm.lockNow()
                        ToolResult(true, "Экран заблокирован.")
                    } else {
                        ToolResult(
                            false,
                            SpecialAccess.required(SpecialAccess.DEVICE_ADMIN)
                        )
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "device_admin_enable"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    if (dpm.isAdminActive(adminComponent)) {
                        return@withContext honestResult(DeviceAdminDecision.alreadyActive())
                    }
                    // Built WITHOUT FLAG_ACTIVITY_NEW_TASK: the UI launches it from an Activity via
                    // ActivityResultLauncher, which is how the official sample shows the screen.
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        // `adminComponent`, not `component`: inside this `apply` block an unqualified
                        // `component` would be `Intent.getComponent()` (null) and the system screen
                        // would reject the request with "No component specified" (BUG-021).
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Нужно, чтобы ассистент мог блокировать экран."
                        )
                    }
                    // Honest no-UI failure when the app is not foreground (requestBoundedUi), and a
                    // bounded wait so a broadcast caller never holds its goAsync window.
                    val bridgeResult = uiBridge.requestBoundedUi(UiRequest.DeviceAdminRequest(intent))
                    val (outcome, failureMessage) = when (bridgeResult) {
                        is UiResult.ActivityResult -> DeviceAdminUiOutcome.COMPLETED to null
                        UiResult.Cancelled -> DeviceAdminUiOutcome.CANCELLED to null
                        is UiResult.Failed -> DeviceAdminUiOutcome.FAILED to bridgeResult.message
                        else -> DeviceAdminUiOutcome.UNEXPECTED to null
                    }
                    honestResult(
                        DeviceAdminDecision.enable(
                            adminActiveBefore = false,
                            outcome = outcome,
                            adminActiveAfter = dpm.isAdminActive(adminComponent),
                            failureMessage = failureMessage,
                        )
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    /** Maps the pure decision onto the tool result the orchestrator sees. */
    private fun honestResult(result: DeviceAdminEnableResult) = ToolResult(result.success, result.message)
}
