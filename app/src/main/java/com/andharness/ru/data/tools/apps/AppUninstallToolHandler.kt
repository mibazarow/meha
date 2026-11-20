package com.andharness.ru.data.tools.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `app_uninstall`: opens the system uninstall confirmation dialog for a package. */
internal class AppUninstallToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "app_uninstall"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val pkgArg = arguments["package"] as? JsonPrimitive
            if (pkgArg == null || pkgArg.content.isEmpty()) {
                return@withContext ToolResult(false, "Укажите package.")
            }
            val pkg = pkgArg.content
            val intent = Intent(Intent.ACTION_DELETE).setData(Uri.parse("package:$pkg"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(true, "Открыт системный диалог удаления для $pkg — подтвердите удаление там.")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
