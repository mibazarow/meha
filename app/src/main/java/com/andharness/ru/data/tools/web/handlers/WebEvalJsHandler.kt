package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject

internal class WebEvalJsHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_eval_js"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        if (tab.view == null) return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val script = arguments.str("script")
            ?: return@guarded ToolResult(false, "Укажите script — JavaScript для выполнения.")
        val raw = host.evalJs(tab, script)
        if (raw == null) {
            return@guarded ToolResult(false, "JavaScript не вернул результат (ошибка выполнения).")
        }
        val truncated = raw.length > WebEngineConsts.MAX_EVAL_CHARS
        ToolResult(
            true,
            buildString {
                if (truncated) appendLine("(результат обрезан до ${WebEngineConsts.MAX_EVAL_CHARS} символов из ${raw.length})")
                append(raw.take(WebEngineConsts.MAX_EVAL_CHARS))
            }.trim(),
        )
    }
}
