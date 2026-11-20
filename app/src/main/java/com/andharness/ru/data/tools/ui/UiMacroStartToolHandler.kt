package com.andharness.ru.data.tools.ui

import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ui_macro_start`: begins a macro recording. */
internal class UiMacroStartToolHandler(
    private val recorder: UiMacroRecorder,
) : ToolHandler {
    override val name = "ui_macro_start"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        try {
            val name = arguments.str("name")
                ?: return@withContext ToolResult(false, "Укажите name — имя макроса.")
            recorder.start(name)
            ToolResult(
                true,
                "Запись макроса «$name» начата. Выполните действия (ui_tap, ui_swipe, ui_type и т.д.), " +
                    "затем вызовите ui_macro_stop. Предыдущая незавершённая запись отменена."
            )
        } catch (e: CancellationException) {
            // A cancel/withTimeout must reach the run loop instead of being reported as a
            // normal tool failure (update.md bug #7) — otherwise the timeout is defeated.
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
