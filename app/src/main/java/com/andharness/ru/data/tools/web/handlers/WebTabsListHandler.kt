package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject

internal class WebTabsListHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_tabs_list"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        if (host.tabs.isEmpty()) return@guarded ToolResult(true, "Вкладок нет.")
        ToolResult(true, host.tabsSummary())
    }
}
