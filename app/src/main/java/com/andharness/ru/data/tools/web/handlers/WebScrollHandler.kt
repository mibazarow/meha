package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

internal class WebScrollHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_scroll"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        if (tab.view == null) return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val direction = (arguments.str("direction") ?: "down").lowercase()
        val amount = arguments.str("amount")?.toIntOrNull() ?: 800
        val js = when (direction) {
            "top" -> "(function(){window.scrollTo(0,0);return 'OK';})()"
            "bottom" -> "(function(){window.scrollTo(0,document.body.scrollHeight);return 'OK';})()"
            "up" -> "(function(){window.scrollBy(0,${-kotlin.math.abs(amount)});return 'OK';})()"
            else -> "(function(){window.scrollBy(0,${kotlin.math.abs(amount)});return 'OK';})()"
        }
        host.evalJs(tab, js)
        delay(WebEngineConsts.SCROLL_SETTLE_MS)
        ToolResult(true, "Прокрутил: $direction.")
    }
}
