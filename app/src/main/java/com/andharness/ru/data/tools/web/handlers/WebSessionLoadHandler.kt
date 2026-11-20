package com.andharness.ru.data.tools.web.handlers

import android.webkit.CookieManager
import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class WebSessionLoadHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_session_load"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val name = arguments.str("name") ?: return@guarded ToolResult(false, "Укажите name — имя сессии.")
        val file = host.sessionFile(name)
        if (!file.exists()) {
            return@guarded ToolResult(false, "Сессия «$name» не найдена (нет файла «${file.absolutePath}»).")
        }
        val obj = try {
            Json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: Exception) {
            return@guarded ToolResult(false, "Файл сессии «$name» повреждён: ${e.message}")
        }
        val url = (obj["url"] as? JsonPrimitive)?.content.orEmpty()
        val cookie = (obj["cookie"] as? JsonPrimitive)?.content.orEmpty()
        if (url.isBlank() || cookie.isBlank()) {
            return@guarded ToolResult(false, "Файл сессии «$name» не содержит url/cookie.")
        }
        withContext(Dispatchers.Main.immediate) {
            val manager = CookieManager.getInstance()
            manager.setCookie(url, cookie)
            manager.flush()
        }
        ToolResult(
            true,
            "Cookie-сессия «$name» восстановлена для «$url». Откройте страницу (web_navigate) — куки применятся.",
        )
    }
}
