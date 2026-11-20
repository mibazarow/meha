package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebBrowserConfig
import com.andharness.ru.data.tools.web.WebContentExtractor
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject

internal class WebExtractHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_extract"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        if (tab.view == null) return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val maxChars = arguments.str("max_chars")?.toIntOrNull() ?: WebEngineConsts.MAX_TEXT_CHARS
        val raw = host.decode(host.evalJs(tab, WebContentExtractor.EXTRACT_JS))
        val parsed = host.parseExtract(raw)
            ?: return@guarded ToolResult(false, "Не удалось извлечь текст страницы.")
        // Honest bot-stub detection: a loaded page may still be a WAF/captcha challenge.
        WebBrowserConfig.detectBotStub("", parsed, 0)?.let {
            return@guarded ToolResult(false, it.reason)
        }
        ToolResult(true, parsed.take(maxChars.coerceIn(500, WebEngineConsts.MAX_TEXT_CHARS)))
    }
}
