package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject

internal class WebOpenHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_open"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val url = arguments.str("url") ?: return@guarded ToolResult(false, "Укажите url.")
        if (!host.isHttpUrl(url)) {
            return@guarded ToolResult(false, "Поддерживаются только http/https ссылки.")
        }
        val tab = host.ensureActiveTab()
        val loaded = host.load(tab, url)
        if (!loaded) {
            ToolResult(false, "Не удалось загрузить «$url» (ошибка, таймаут или блокировка).")
        } else {
            ToolResult(true, host.pageSummary(tab))
        }
    }
}
