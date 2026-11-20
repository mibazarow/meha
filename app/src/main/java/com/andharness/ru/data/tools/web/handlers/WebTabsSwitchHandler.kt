package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject

internal class WebTabsSwitchHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_tabs_switch"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        if (host.tabs.isEmpty()) return@guarded ToolResult(false, "Вкладок нет — создайте вкладку (web_tabs_new).")
        val indexArg = arguments.str("index")
        val nameArg = arguments.str("name")
        if (indexArg == null && nameArg == null) {
            return@guarded ToolResult(false, "Укажите index или name вкладки.")
        }
        val target = host.resolveTab(indexArg, nameArg)
            ?: return@guarded ToolResult(false, "Вкладка не найдена (index=${indexArg ?: "-"}, name=${nameArg ?: "-"}).")
        host.activeIndex = host.tabs.indexOf(target)
        ToolResult(true, "Активная вкладка: ${host.activeIndex}.\n${host.tabsSummary()}")
    }
}
