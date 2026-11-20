package com.andharness.ru.data.tools.apps

import android.net.Uri
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `app_store_open`: opens a Google Play app page or search, by `package` or `query`. */
internal class AppStoreOpenToolHandler(
    private val store: AppStoreLauncher,
) : ToolHandler {

    override val name = "app_store_open"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val pkg = (arguments["package"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val query = (arguments["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (pkg.isEmpty() && query.isEmpty()) {
                return@withContext ToolResult(false, "Укажите package или query.")
            }
            val target: String
            val opened: Boolean
            if (pkg.isNotEmpty()) {
                val id = Uri.encode(pkg)
                target = "страницу Google Play для «$pkg»"
                opened = store.open(
                    market = Uri.parse("market://details?id=$id"),
                    web = Uri.parse("https://play.google.com/store/apps/details?id=$id"),
                )
            } else {
                val encoded = Uri.encode(query)
                target = "поиск Google Play по запросу «$query»"
                opened = store.open(
                    market = Uri.parse("market://search?q=$encoded"),
                    web = Uri.parse("https://play.google.com/store/search?q=$encoded"),
                )
            }
            ToolResult(true, store.message(target, opened))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
