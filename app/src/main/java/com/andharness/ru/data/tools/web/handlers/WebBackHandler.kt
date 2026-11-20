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

internal class WebBackHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_back"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "История пуста (страница не открывалась).")
        val view = tab.view ?: return@guarded ToolResult(false, "История пуста (страница не открывалась).")
        val canGoBack = withContext(Dispatchers.Main.immediate) { view.canGoBack() }
        if (!canGoBack) return@guarded ToolResult(false, "Назад некуда — это первая страница.")
        tab.signal = CompletableDeferred()
        withContext(Dispatchers.Main.immediate) { view.goBack() }
        val loaded = withTimeoutOrNull(WebEngineConsts.LOAD_TIMEOUT_MS) { tab.signal.await() }
        delay(WebEngineConsts.SETTLE_MS)
        if (loaded == null) {
            ToolResult(
                false,
                "Таймаут: страница не загрузилась после возврата назад за " +
                    "${WebEngineConsts.LOAD_TIMEOUT_MS / 1000}с.\n${host.pageSummary(tab)}",
            )
        } else {
            ToolResult(true, "Вернулся назад.\n${host.pageSummary(tab)}")
        }
    }
}
