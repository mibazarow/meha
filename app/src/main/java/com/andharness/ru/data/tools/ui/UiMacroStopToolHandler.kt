package com.andharness.ru.data.tools.ui

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** `ui_macro_stop`: finishes a macro recording and persists it. */
internal class UiMacroStopToolHandler(
    private val recorder: UiMacroRecorder,
    private val macros: UiMacroStore,
) : ToolHandler {
    override val name = "ui_macro_stop"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        try {
            val snapshot = recorder.stop()
                ?: return@withContext ToolResult(
                    false,
                    "Запись не активна: вызовите ui_macro_start перед выполнением действий."
                )
            if (snapshot.steps.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "Не записано ни одного шага — макрос «${snapshot.name}» не сохранён. " +
                        "Действия записываются, только когда вызываются инструменты ui_* при активной записи."
                )
            }
            val atLimit = macros.count() >= UiMacroCodec.MAX_MACROS
            val record = UiMacroRecord(
                id = UUID.randomUUID().toString(),
                name = snapshot.name,
                createdAt = System.currentTimeMillis(),
                steps = snapshot.steps,
            )
            if (!macros.put(record)) {
                return@withContext ToolResult(false, "Не удалось сохранить макрос «${snapshot.name}».")
            }
            val notes = buildString {
                if (snapshot.overflow) {
                    append(" Достигнут лимит ${UiMacroCodec.MAX_STEPS} шагов — лишние действия не записаны.")
                }
                if (atLimit) {
                    append(" Достигнут лимит ${UiMacroCodec.MAX_MACROS} макросов — самый старый удалён.")
                }
            }
            ToolResult(
                true,
                "Макрос «${snapshot.name}» сохранён: id=${record.id}, шагов ${snapshot.steps.size}.$notes"
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
