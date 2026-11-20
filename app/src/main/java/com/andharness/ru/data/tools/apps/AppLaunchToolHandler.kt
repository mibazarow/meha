package com.andharness.ru.data.tools.apps

import android.content.Context
import android.content.Intent
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `app_launch`: launches an application's front-door activity. */
internal class AppLaunchToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "app_launch"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val pkgArg = arguments["package"] as? JsonPrimitive
            if (pkgArg == null || pkgArg.content.isEmpty()) {
                return@withContext ToolResult(false, "Укажите package.")
            }
            val pkg = pkgArg.content
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                return@withContext ToolResult(false, "Не найден запускаемый компонент для $pkg")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(true, "Запущено: $pkg")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
