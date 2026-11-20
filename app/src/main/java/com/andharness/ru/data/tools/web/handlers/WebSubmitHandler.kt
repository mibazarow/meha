package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.web.WebContentExtractor
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

internal class WebSubmitHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_submit"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        if (tab.view == null) return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val res = host.decode(host.evalJs(tab, WebContentExtractor.SUBMIT_JS))
        // Submitting usually navigates; give the page time to react before reporting state.
        delay(WebEngineConsts.CLICK_SETTLE_MS)
        if (res == "NO_FORM") {
            ToolResult(false, "На странице нет формы для отправки (кнопку можно нажать через web_click).")
        } else {
            ToolResult(true, "Форма отправлена.\n${host.pageSummary(tab)}")
        }
    }
}
