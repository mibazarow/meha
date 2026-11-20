package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

internal class WebWaitHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_wait"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        if (tab.view == null) return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val selector = arguments.str("selector")
        val timeoutSec = arguments.str("timeout_sec")?.toIntOrNull()?.coerceIn(1, 60) ?: 10
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        if (selector == null) {
            while (System.currentTimeMillis() < deadline) {
                val ready = host.decode(host.evalJs(tab, "document.readyState"))
                if (ready == "complete") {
                    return@guarded ToolResult(true, "Страница загружена (readyState=complete).")
                }
                delay(WebEngineConsts.POLL_INTERVAL_MS)
            }
            ToolResult(false, "Не дождался готовности страницы за $timeoutSec с.")
        } else {
            val js =
                "(function(){return document.querySelector(${host.jsonStr(selector)})?'YES':'NO';})()"
            while (System.currentTimeMillis() < deadline) {
                if (host.decode(host.evalJs(tab, js)) == "YES") {
                    return@guarded ToolResult(true, "Элемент «$selector» появился на странице.")
                }
                delay(WebEngineConsts.POLL_INTERVAL_MS)
            }
            ToolResult(false, "Элемент «$selector» не появился за $timeoutSec с.")
        }
    }
}
