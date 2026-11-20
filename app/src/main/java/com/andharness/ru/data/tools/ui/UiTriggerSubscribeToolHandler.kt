package com.andharness.ru.data.tools.ui

import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.service.AccessibilityAutomationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ui_trigger_subscribe`: starts/stops/lists the window-event recording (TYPE_WINDOW_STATE_CHANGED). */
internal class UiTriggerSubscribeToolHandler : ToolHandler {
    override val name = "ui_trigger_subscribe"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val rawAction = arguments.str("action")
                ?: return@withContext ToolResult(false, "Укажите action: start | stop | list.")
            when (rawAction.lowercase()) {
                "start", "старт" -> {
                    if (!AccessibilityAutomationService.isEnabled()) return@withContext notEnabled()
                    UiEventRecorder.start()
                    ToolResult(
                        true,
                        "Запись событий окон включена: фиксируются смена приложения/окна " +
                            "(TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED). Читать — action=list."
                    )
                }
                "stop", "стоп" -> {
                    val wasRecording = UiEventRecorder.isRecording()
                    UiEventRecorder.stop()
                    if (wasRecording) ToolResult(true, "Запись событий окон остановлена (события сохранены).")
                    else ToolResult(true, "Запись событий окон и так не была активна.")
                }
                "list", "список" -> {
                    if (!AccessibilityAutomationService.isEnabled()) return@withContext notEnabled()
                    val limit = arguments.str("limit")?.toIntOrNull()?.coerceIn(1, UiEventRecorder.MAX_EVENTS) ?: 50
                    val events = UiEventRecorder.recent(limit)
                    if (events.isEmpty()) {
                        ToolResult(
                            true,
                            if (UiEventRecorder.isRecording()) "Событий пока нет — подождите смены приложения."
                            else "Запись не активна и событий нет. Сначала вызовите action=start."
                        )
                    } else {
                        val header = if (UiEventRecorder.isRecording()) "Запись активна" else "Запись остановлена"
                        ToolResult(true, "$header, последние ${events.size}:\n" + events.joinToString("\n") {
                            "${it.timeMs} | ${it.packageName} | ${it.className ?: "—"} | ${it.eventName}"
                        })
                    }
                }
                else -> ToolResult(
                    false,
                    "Неизвестный action «$rawAction». Доступно: start | stop | list."
                )
            }
        } catch (e: CancellationException) {
            // A cancel/withTimeout must reach the run loop instead of being reported as a
            // normal tool failure (update.md bug #7) — otherwise the timeout is defeated.
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    private fun notEnabled(): ToolResult = ToolResult(
        false,
        com.andharness.ru.domain.permission.SpecialAccess.required(
            com.andharness.ru.domain.permission.SpecialAccess.ACCESSIBILITY
        )
    )
}
