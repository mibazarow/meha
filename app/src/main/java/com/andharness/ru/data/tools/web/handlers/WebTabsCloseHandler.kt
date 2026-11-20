package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal class WebTabsCloseHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_tabs_close"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        if (host.tabs.isEmpty()) return@guarded ToolResult(false, "Вкладок нет.")
        val indexArg = arguments.str("index")
        val nameArg = arguments.str("name")
        val target = if (indexArg == null && nameArg == null) {
            host.activeTab()
        } else {
            host.resolveTab(indexArg, nameArg)
        } ?: return@guarded ToolResult(false, "Вкладка не найдена (index=${indexArg ?: "-"}, name=${nameArg ?: "-"}).")
        val idx = host.tabs.indexOf(target)
        host.tabs.removeAt(idx)
        withContext(Dispatchers.Main.immediate) { target.view?.destroy() }
        host.activeIndex = when {
            host.tabs.isEmpty() -> -1
            idx < host.activeIndex -> host.activeIndex - 1
            idx == host.activeIndex -> idx.coerceAtMost(host.tabs.lastIndex)
            else -> host.activeIndex
        }
        ToolResult(true, "Вкладка $idx закрыта.\n${host.tabsSummary()}")
    }
}
