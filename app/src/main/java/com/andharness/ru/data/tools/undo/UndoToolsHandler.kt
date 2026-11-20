package com.andharness.ru.data.tools.undo

import com.andharness.ru.data.undo.UndoStore
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.undo.UndoExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the two M3 undo tools:
 * - [undo_list] — read-only feed of recent undoable actions (newest first, with a human
 *   description);
 * - [undo_last] — reverts the most recent undoable action through [UndoExecutor], and says honestly
 *   when there is nothing to undo or the inverse cannot be applied. The journal entry is removed
 *   only after the inverse succeeds, so a failed undo stays in the journal and can be retried.
 *
 * Honesty is the point of this slice: an action is in the journal ONLY when its inverse is known, so
 * `undo_last` never claims to have reverted something it cannot actually revert.
 */
class UndoToolsHandler(
    private val store: UndoStore,
    private val executor: UndoExecutor,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "undo_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val requested = (arguments["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
                    val limit = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
                    val entries = store.list(limit)
                    if (entries.isEmpty()) {
                        return@withContext ToolResult(
                            true,
                            "Отменяемых действий нет: журнал отмены пуст.",
                        )
                    }
                    ToolResult(
                        true,
                        entries.joinToString("\n") { entry ->
                            "#${entry.id.take(8)} | ${
                                formatTime(entry.timestamp)
                            } | ${entry.toolName} | ${entry.description}"
                        },
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка чтения журнала отмены: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "undo_last"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    // Peek the newest entry without removing it: the journal must keep the entry
                    // until its inverse has actually succeeded, so a failure never loses it.
                    val entry = store.all().lastOrNull()
                        ?: return@withContext ToolResult(
                            false,
                            "Нечего отменять: журнал отмены пуст. Отменять можно только действия, " +
                                "для которых приложение умеет выполнить обратную операцию " +
                                "(например, создание заметки, задачи или записи памяти).",
                        )
                    val outcome = executor.execute(entry.undoKind, entry.payload)
                    if (outcome.success) {
                        // Remove by id only after success, so the journal loses exactly the entry
                        // that was actually reverted.
                        store.remove(entry.id)
                        ToolResult(true, "Отменено: ${entry.description}. ${outcome.message}")
                    } else {
                        ToolResult(
                            false,
                            "Не удалось отменить «${entry.description}»: ${outcome.message}. " +
                                "Запись сохранена в журнале — попытку можно повторить.",
                        )
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка отмены: ${e.message}")
                }
            }
        },
    )

    private companion object {
        /** Default number of rows [undo_list] shows. */
        const val DEFAULT_LIMIT = 20

        /** Hard cap on rows [undo_list] returns (bounded output). */
        const val MAX_LIMIT = 50

        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.US)

        fun formatTime(epochMillis: Long): String = try {
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
        } catch (_: Exception) {
            epochMillis.toString()
        }
    }
}
