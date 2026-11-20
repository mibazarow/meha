package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebContentExtractor
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

internal class WebClickHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_click"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        if (tab.view == null) return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val selector = arguments.str("selector")
        val text = arguments.str("text")
        if (selector == null && text == null) {
            return@guarded ToolResult(false, "Укажите selector и/или text для клика.")
        }
        val js = WebContentExtractor.clickJs(selector, text)
        val res = host.decode(host.evalJs(tab, js))
        // A click may trigger navigation; give the page time to react, then report state.
        delay(WebEngineConsts.CLICK_SETTLE_MS)
        if (res == "NOT_FOUND") {
            ToolResult(false, "Элемент для клика не найден (selector=${selector ?: "-"}, text=${text ?: "-"}).")
        } else {
            ToolResult(true, "Клик выполнен.\n${host.pageSummary(tab)}")
        }
    }
}
