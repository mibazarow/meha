package com.andharness.ru.data.tools.ui

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ui_macro_list`: lists persisted UI macros. */
internal class UiMacroListToolHandler(
    private val macros: UiMacroStore,
) : ToolHandler {
    override val name = "ui_macro_list"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        try {
            val all = macros.list()
            if (all.isEmpty()) {
                ToolResult(true, "Макросы не найдены. Записать: ui_macro_start → действия → ui_macro_stop.")
            } else {
                ToolResult(true, "Макросы (${all.size}):\n" + all.joinToString("\n") {
                    "${it.id} | ${it.name} | шагов ${it.steps.size}"
                })
            }
        } catch (e: CancellationException) {
            // A cancel/withTimeout must reach the run loop instead of being reported as a
            // normal tool failure (update.md bug #7) — otherwise the timeout is defeated.
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
