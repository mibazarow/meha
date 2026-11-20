package com.andharness.ru.data.tools.insights

import com.andharness.ru.data.insights.InsightsAssembler
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.insights.InsightAnalyzer
import com.andharness.ru.domain.insights.InsightKind
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Builds the read-only `insights_list` handler (checklist §1 «Проактивные инсайты»).
 *
 * It surfaces a small set of honest, data-backed insights about the user's own usage, derived only
 * from local signals ([InsightsAssembler]). Optional `kind` filters the section
 * (`routine` / `stale_skill` / `notification_load`) and `limit` caps the result. When nothing
 * qualifies the tool returns the honest "Инсайтов пока нет…" message — it never invents a habit,
 * a trend or a number.
 */
class InsightsToolsHandler(
    private val assembler: InsightsAssembler,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "insights_list"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val rawKind = arguments.str("kind")
                    val kind = rawKind?.let { InsightKind.fromToken(it) }
                    if (rawKind != null && kind == null) {
                        return@withContext ToolResult(
                            false,
                            "Неизвестный kind «$rawKind». Доступные значения: " +
                                InsightKind.tokens.joinToString(", ") + ".",
                        )
                    }
                    val limit = (arguments.str("limit")?.toIntOrNull() ?: DEFAULT_LIMIT)
                        .coerceIn(1, MAX_LIMIT)
                    val insights = assembler.collect(
                        kinds = kind?.let { setOf(it) } ?: emptySet(),
                        limit = limit,
                    )
                    ToolResult(true, InsightAnalyzer.render(insights))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка сбора инсайтов: ${e.message}")
                }
            }
        },
    )

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
    }
}
