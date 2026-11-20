package com.andharness.ru.data.tools.web.handlers

import android.webkit.CookieManager
import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class WebSessionSaveHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_session_save"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val name = arguments.str("name") ?: return@guarded ToolResult(false, "Укажите name — имя сессии.")
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val url = host.decode(host.evalJs(tab, "location.href")) ?: tab.url
        if (!host.isHttpUrl(url)) {
            return@guarded ToolResult(false, "Некуда сохранять: на активной вкладке нет http/https страницы.")
        }
        val cookie = withContext(Dispatchers.Main.immediate) {
            CookieManager.getInstance().getCookie(url)
        }.orEmpty()
        if (cookie.isBlank()) {
            return@guarded ToolResult(false, "Для «$url» нет cookie — сохранять нечего.")
        }
        val obj = buildJsonObject {
            put("url", url)
            put("cookie", cookie)
            put("saved_at", System.currentTimeMillis().toString())
        }
        val file = host.sessionFile(name)
        val text = Json.encodeToString(JsonElement.serializer(), obj)
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
        if (!file.exists() || file.length() <= 0L) {
            return@guarded ToolResult(false, "Не удалось записать файл сессии «${file.absolutePath}».")
        }
        ToolResult(
            true,
            "Cookie-сессия «$name» сохранена для «$url» (${cookie.length} символов) в «${file.absolutePath}».",
        )
    }
}
