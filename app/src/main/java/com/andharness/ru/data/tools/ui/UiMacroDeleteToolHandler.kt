package com.andharness.ru.data.tools.ui

import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ui_macro_delete`: deletes a persisted UI macro by id or name. */
internal class UiMacroDeleteToolHandler(
    private val macros: UiMacroStore,
) : ToolHandler {
    override val name = "ui_macro_delete"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        try {
            val target = arguments.str("id") ?: arguments.str("name")
                ?: return@withContext ToolResult(false, "Укажите id или name макроса.")
            if (macros.delete(target)) {
                ToolResult(true, "Макрос «$target» удалён.")
            } else {
                ToolResult(false, "Макрос не найден: «$target».")
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
