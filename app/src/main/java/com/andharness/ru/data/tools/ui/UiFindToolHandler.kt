package com.andharness.ru.data.tools.ui

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ui_find`: returns ranked matches for a selector against the full accessibility hierarchy. */
internal class UiFindToolHandler(
    private val engine: UiActionEngine,
) : ToolHandler {
    override val name = "ui_find"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val service = engine.serviceOrNull() ?: return@withContext engine.notEnabled()
        try {
            val result = engine.findAction(service, arguments)
            if (result.success) engine.autoRecordScreen(service)
            result
        } catch (e: CancellationException) {
            // A cancel/withTimeout must reach the run loop instead of being reported as a
            // normal tool failure (update.md bug #7) — otherwise the timeout is defeated.
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
