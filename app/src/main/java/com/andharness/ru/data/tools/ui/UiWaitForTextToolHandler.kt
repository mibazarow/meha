package com.andharness.ru.data.tools.ui

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** `ui_wait_for_text`: polls the accessibility tree until a selector matches or the timeout elapses. */
internal class UiWaitForTextToolHandler(
    private val engine: UiActionEngine,
) : ToolHandler {
    override val name = "ui_wait_for_text"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        val service = engine.serviceOrNull() ?: return engine.notEnabled()
        return try {
            engine.waitForTextAction(service, arguments)
        } catch (e: CancellationException) {
            // A cancel/withTimeout must reach the run loop instead of being reported as a
            // normal tool failure (update.md bug #7) — otherwise the timeout is defeated.
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
