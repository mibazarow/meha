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

internal class WebReloadHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_reload"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val view = tab.view ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        tab.signal = CompletableDeferred()
        withContext(Dispatchers.Main.immediate) { view.reload() }
        val loaded = withTimeoutOrNull(WebEngineConsts.LOAD_TIMEOUT_MS) { tab.signal.await() }
        delay(WebEngineConsts.SETTLE_MS)
        if (loaded == null) {
            ToolResult(
                false,
                "Таймаут: страница не перезагрузилась за ${WebEngineConsts.LOAD_TIMEOUT_MS / 1000}с.\n${host.pageSummary(tab)}",
            )
        } else {
            ToolResult(true, "Страница перезагружена.\n${host.pageSummary(tab)}")
        }
    }
}
