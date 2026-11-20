package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebBrowserConfig
import com.andharness.ru.data.tools.web.WebContentExtractor
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject

internal class WebReadableHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_readable"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        if (tab.view == null) return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val maxChars = arguments.str("max_chars")?.toIntOrNull()
            ?.coerceIn(1_000, WebEngineConsts.MAX_READABLE_CHARS) ?: WebEngineConsts.DEFAULT_READABLE_CHARS
        val raw = host.evalJs(tab, WebContentExtractor.READABLE_JS)
        val parsed = host.parseExtract(host.decode(raw))
            ?: return@guarded ToolResult(false, "Не удалось извлечь основной текст страницы.")
        // Honest bot-stub detection: a loaded page may still be a WAF/captcha challenge.
        WebBrowserConfig.detectBotStub("", parsed, 0)?.let {
            return@guarded ToolResult(false, it.reason)
        }
        val truncated = parsed.length > maxChars
        val text = parsed.take(maxChars)
        val url = host.decode(host.evalJs(tab, "location.href")).orEmpty()
        ToolResult(
            true,
            buildString {
                appendLine("Читаемый текст страницы $url")
                if (truncated) {
                    appendLine("(текст обрезан до $maxChars символов из ${parsed.length})")
                }
                appendLine()
                append(text)
            }.trim(),
        )
    }
}
