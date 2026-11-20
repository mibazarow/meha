package com.andharness.ru.data.tools.apps

import android.net.Uri
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `app_store_search`: opens a Google Play search for a query. */
internal class AppStoreSearchToolHandler(
    private val store: AppStoreLauncher,
) : ToolHandler {

    override val name = "app_store_search"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = (arguments["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (query.isEmpty()) return@withContext ToolResult(false, "Укажите query.")
            val encoded = Uri.encode(query)
            val opened = store.open(
                market = Uri.parse("market://search?q=$encoded"),
                web = Uri.parse("https://play.google.com/store/search?q=$encoded"),
            )
            ToolResult(true, store.message("поиск Google Play по запросу «$query»", opened))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
