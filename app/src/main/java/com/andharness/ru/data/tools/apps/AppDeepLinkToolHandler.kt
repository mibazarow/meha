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

/** `app_deep_link`: opens a URL, optionally restricted to a package. */
internal class AppDeepLinkToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "app_deep_link"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val url = (arguments["url"] as? JsonPrimitive)?.content
                ?: return@withContext ToolResult(false, "Укажите url.")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            (arguments["package"] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
            context.startActivity(intent)
            ToolResult(
                true,
                "Ссылка открыта: $url. Страницу и действия в ней выполняет пользователь — " +
                    "агент не подтверждает, что там что-то произошло.",
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
