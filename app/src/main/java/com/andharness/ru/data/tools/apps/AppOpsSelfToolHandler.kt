package com.andharness.ru.data.tools.apps

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `app_ops_self`: reports AppOps modes for this app's own uid, optionally for a single `op`.
 * Ordinary apps can only read their OWN uid: other packages require a privileged permission.
 * https://developer.android.com/reference/android/app/AppOpsManager
 */
internal class AppOpsSelfToolHandler(
    private val context: Context,
    private val ops: AppOpsAccess,
) : ToolHandler {

    override val name = "app_ops_self"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val appOps = context.getSystemService(AppOpsManager::class.java)
                ?: return@withContext ToolResult(false, "AppOpsManager недоступен на устройстве.")
            val uid = Process.myUid()
            val pkg = context.packageName
            val requested = (arguments["op"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val opsList = if (requested.isNotEmpty()) listOf(requested) else AppToolLimits.SELF_APP_OPS
            val lines = opsList.map { op ->
                val mode = ops.checkMode(appOps, op, uid, pkg)
                if (mode == null) {
                    "$op — неизвестная этой версии Android операция"
                } else {
                    "$op — ${ops.modeName(mode)} (код $mode)"
                }
            }
            ToolResult(
                true,
                buildString {
                    appendLine("AppOps собственного приложения ($pkg, uid=$uid):")
                    appendLine(lines.joinToString("\n"))
                    append("Обычному приложению доступно чтение AppOps только для своего UID; ")
                    append("состояние операций других пакетов прочитать нельзя.")
                }
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
