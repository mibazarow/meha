package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.data.tools.web.WebSearchClient
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

internal class WebSearchHandler(
    private val host: WebEngineHost,
    private val search: WebSearchClient,
) : ToolHandler {
    override val name = "web_search"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val query = arguments.str("query") ?: return@guarded ToolResult(false, "Укажите query.")
        val limit = arguments.str("limit")?.toIntOrNull()?.coerceIn(1, 30) ?: 10
        val searchUrl = WebSearchClient.SEARCH_URL + URLEncoder.encode(query, "UTF-8")

        val tab = host.ensureActiveTab()
        var results = emptyList<WebSearchClient.Result>()
        if (host.load(tab, searchUrl)) {
            results = search.resultsFromJs(host.evalJs(tab, WebSearchClient.SEARCH_JS))
        }
        if (results.isEmpty()) {
            // Fallback: fetch the plain HTML of the results page and parse it with a regex.
            results = search.htmlFallback(searchUrl)
        }
        val limited = results.take(limit)
        if (limited.isEmpty()) {
            return@guarded ToolResult(
                false,
                "Поиск «$query» не дал результатов (выдача пуста, изменилась разметка или сайт " +
                    "заблокировал доступ). Данные не выдумываю.",
            )
        }
        ToolResult(
            true,
            buildString {
                appendLine("Результаты поиска «$query» (${limited.size} из ${results.size}):")
                limited.forEachIndexed { i, r ->
                    appendLine("${i + 1}. ${r.title.ifBlank { "(без заголовка)" }}")
                    appendLine("   ${r.url}")
                    if (r.snippet.isNotBlank()) appendLine("   ${r.snippet.take(300)}")
                }
            }.trim(),
        )
    }
}
