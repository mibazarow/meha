package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

internal class WebForwardHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_forward"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "История пуста (страница не открывалась).")
        val view = tab.view ?: return@guarded ToolResult(false, "История пуста (страница не открывалась).")
        val canGoForward = withContext(Dispatchers.Main.immediate) { view.canGoForward() }
        if (!canGoForward) {
            return@guarded ToolResult(false, "Вперёд некуда — это последняя страница в истории.")
        }
        tab.signal = CompletableDeferred()
        withContext(Dispatchers.Main.immediate) { view.goForward() }
        val loaded = withTimeoutOrNull(WebEngineConsts.LOAD_TIMEOUT_MS) { tab.signal.await() }
        delay(WebEngineConsts.SETTLE_MS)
        if (loaded == null) {
            ToolResult(
                false,
                "Таймаут: страница не загрузилась после перехода вперёд за " +
                    "${WebEngineConsts.LOAD_TIMEOUT_MS / 1000}с.\n${host.pageSummary(tab)}",
            )
        } else {
            ToolResult(true, "Перешёл вперёд по истории.\n${host.pageSummary(tab)}")
        }
    }
}
