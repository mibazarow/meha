package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject

internal class WebTabsNewHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_tabs_new"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val url = arguments.str("url")
        val name = arguments.str("name")
        if (url != null && !host.isHttpUrl(url)) {
            return@guarded ToolResult(false, "Поддерживаются только http/https ссылки.")
        }
        val tab = host.createTab(name)
        if (url != null) {
            if (!host.load(tab, url)) {
                return@guarded ToolResult(
                    false,
                    "Вкладка ${host.tabs.indexOf(tab)} создана, но «$url» не загрузился.",
                )
            }
        }
        ToolResult(true, "Создана вкладка ${host.tabs.indexOf(tab)}.\n${host.tabsSummary()}")
    }
}
