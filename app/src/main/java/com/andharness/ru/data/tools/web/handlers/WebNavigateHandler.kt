package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject

internal class WebNavigateHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_navigate"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val url = arguments.str("url") ?: return@guarded ToolResult(false, "Укажите url.")
        if (!host.isHttpUrl(url)) {
            return@guarded ToolResult(false, "Поддерживаются только http/https ссылки.")
        }
        val tab = host.ensureActiveTab()
        val loaded = host.load(tab, url)
        if (!loaded) {
            return@guarded ToolResult(
                false,
                "Навигация не удалась: «$url» не загрузился (ошибка, таймаут или блокировка).",
            )
        }
        val finalUrl = host.decode(host.evalJs(tab, "location.href")) ?: tab.url
        val title = host.decode(host.evalJs(tab, "document.title")).orEmpty()
        ToolResult(
            true,
            buildString {
                appendLine("Перешёл по URL.")
                appendLine("Итоговый URL: $finalUrl")
                if (title.isNotBlank()) appendLine("Заголовок: ${title.take(300)}")
            }.trim(),
        )
    }
}
