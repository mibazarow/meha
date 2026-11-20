package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.ImageSearchClient
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject

internal class ImageSearchHandler(
    private val host: WebEngineHost,
    private val imageSearch: ImageSearchClient,
) : ToolHandler {
    override val name = "image_search"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val query = arguments.str("query") ?: return@guarded ToolResult(false, "Укажите query.")
        val count = when (val c = arguments.str("count")?.toIntOrNull()) {
            null -> 5
            else -> c.coerceIn(1, 10)
        }
        val orientation = arguments.str("orientation")
        val source = when (arguments.str("source")?.lowercase()) {
            "wikimedia" -> ImageSearchClient.Src.WIKIMEDIA
            else -> ImageSearchClient.Src.OPENVERSE
        }
        val (used, results) = imageSearch.search(query, count, orientation, source)
        if (results.isEmpty()) {
            return@guarded ToolResult(
                false,
                "Поиск изображений «$query» не дал результатов (источник: ${host.sourceLabel(used)}, " +
                    "пусто или обе базы недоступны). Данные не выдумываю.",
            )
        }
        ToolResult(
            true,
            buildString {
                appendLine("Найдено изображений «$query» (источник: ${host.sourceLabel(used)}): ${results.size}")
                results.forEachIndexed { i, r ->
                    appendLine("${i + 1}. ${r.title.ifBlank { "(без заголовка)" }}")
                    appendLine("   ${r.url}")
                    val meta = listOfNotNull(
                        r.author.ifBlank { null },
                        r.license.ifBlank { null },
                        buildString {
                            if (r.width != null && r.height != null) append("${r.width}×${r.height}")
                        }.ifBlank { null },
                    ).joinToString(" | ")
                    if (meta.isNotBlank()) appendLine("   $meta")
                }
            }.trim(),
        )
    }
}
