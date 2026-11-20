package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class WebSetUserAgentHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_set_user_agent"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        // Deliberately not `arg(...)`: a blank `ua` is meaningful (reset to the default).
        val raw = (arguments["ua"] as? JsonPrimitive)?.content
            ?: return@guarded ToolResult(false, "Укажите ua (пустая строка — вернуть значение по умолчанию).")
        val trimmed = raw.trim()
        val applied = if (trimmed.isEmpty()) WebEngineConsts.MOBILE_CHROME_UA else trimmed
        host.customUserAgent = applied
        host.applyUserAgent(applied)
        val human = if (trimmed.isEmpty()) "значение по умолчанию" else "«${applied.take(200)}»"
        ToolResult(true, "User-Agent веб-сессии установлен: $human. Применится и к новым вкладкам.")
    }
}
