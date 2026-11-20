package com.andharness.ru.data.tools.ui

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ui_long_press`: long-presses coordinates or a resolved selector target. */
internal class UiLongPressToolHandler(
    private val engine: UiActionEngine,
) : ToolHandler {
    override val name = "ui_long_press"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val service = engine.serviceOrNull() ?: return@withContext engine.notEnabled()
        engine.recordIfActive("ui_long_press", arguments)
        try {
            engine.longPressAction(service, arguments)
        } catch (e: CancellationException) {
            // A cancel/withTimeout must reach the run loop instead of being reported as a
            // normal tool failure (update.md bug #7) — otherwise the timeout is defeated.
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
