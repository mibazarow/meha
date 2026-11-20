package com.andharness.ru.data.tools.assist

import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.memory.OpinionConfidence
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.undo.UndoKinds
import com.andharness.ru.domain.undo.UndoPayload
import com.andharness.ru.domain.undo.UndoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.andharness.ru.data.tools.str
import java.util.Locale

/**
 * Builds one [ToolHandler] per assist tool: notes ([note_create]/[note_list]/[note_delete]),
 * long-term memory ([memory_save]/[memory_search]/[memory_list]/[memory_delete]) and todos
 * ([todo_add]/[todo_list]/[todo_done]/[todo_delete]).
 */
class AssistToolsHandler(
    private val repository: AssistRepository,
    private val undoRecorder: UndoRecorder = UndoRecorder.NoOp,
) {

    private val kindNote = "note"

    private companion object {
        /** How many ranked memories [memory_search] returns. */
        const val MEMORY_RESULTS = 10

        /** Default/maximum number of rows [memory_list] returns (bounded output). */
        const val MEMORY_LIST_DEFAULT = 30
        const val MEMORY_LIST_MAX = 100

        /** Bounded pool [memory_list] scans before in-memory filtering (no new SQL/migrations). */
        const val MEMORY_LIST_SCAN = 500

        /** Bounded scan used by `note_delete`'s post-check (BUG-059) to confirm rows are gone. */
        const val NOTE_VERIFY_SCAN = 500

        const val KIND_MEMORY = "memory"

        /** Preview length for the human description kept in the undo journal. */
        const val UNDO_PREVIEW_CHARS = 60

        /** How much of a rejected text is quoted back, so the model can see what was dropped. */
        const val REJECT_PREVIEW_CHARS = 60

        /** Default confidence of a new opinion when the model does not supply one. */
        const val DEFAULT_CONFIDENCE = 0.8f

        /** Default/maximum number of opinions [opinion_list] returns (bounded output). */
        const val OPINION_LIST_DEFAULT = 20
        const val OPINION_LIST_MAX = 50

        /** Bounded pool [opinion_list] scans before in-memory filtering (no new SQL/migrations). */
        const val OPINION_LIST_SCAN = 500

        const val ORDER_RECENT = "recent"
        const val ORDER_IMPORTANCE = "importance"

        const val MILLIS_PER_DAY = 86_400_000L
    }

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "note_create"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val text = arguments.str("text") ?: return@withContext ToolResult(false, "Укажите text.")
                    val id = repository.addNote(kindNote, text)
                    undoRecorder.record(
                        toolName = "note_create",
                        description = "Создана заметка #$id: ${text.take(UNDO_PREVIEW_CHARS)}",
                        undoKind = UndoKinds.NOTE_DELETE,
                        payload = mapOf(UndoPayload.ID to id.toString()),
                    )
                    ToolResult(true, "Заметка #$id сохранена.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "note_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val notes = repository.listNotes(kindNote)
                    if (notes.isEmpty()) ToolResult(true, "Заметок нет.")
                    else ToolResult(true, notes.joinToString("\n") { "#${it.id} | ${it.text}" })
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "note_delete"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val id = arguments.str("id")?.toLongOrNull()
                    if (id != null) {
                        val deleted = repository.deleteNoteById(id)
                        // Post-check (BUG-059): success only when the row is really gone.
                        val stillThere = repository.listNotes(kindNote, NOTE_VERIFY_SCAN).any { it.id == id }
                        return@withContext when {
                            !deleted -> ToolResult(false, "Заметка #$id не найдена.")
                            stillThere -> ToolResult(false, "Заметка #$id всё ещё видна после удаления — НЕ подтверждено.")
                            else -> ToolResult(true, "Заметка #$id удалена.")
                        }
                    }
                    val query = arguments.str("query")
                        ?: return@withContext ToolResult(false, "Укажите id или query.")
                    val matches = repository.searchNotes(kindNote, query)
                    if (matches.isEmpty()) return@withContext ToolResult(false, "Заметки по «$query» не найдены.")
                    val ids = matches.map { it.id }
                    val n = ids.count { repository.deleteNoteById(it) }
                    // Post-check (BUG-059): the matched rows must be gone afterwards; zero deletes
                    // or any surviving row is an honest failure, never «готово».
                    val survivors = repository.listNotes(kindNote, NOTE_VERIFY_SCAN).count { it.id in ids }
                    ToolResult(
                        success = n > 0 && survivors == 0,
                        output = when {
                            n == 0 -> "Совпадения есть, но не удалось удалить ни одной заметки — действие НЕ выполнено."
                            survivors > 0 -> "Удалено заметок: $n, но $survivors всё ещё видны — НЕ подтверждено."
                            else -> "Удалено заметок: $n"
                        }
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "memory_save"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val text = arguments.str("text") ?: return@withContext ToolResult(false, "Укажите text.")
                    val tags = arguments.str("tags").orEmpty()
                    val importance = arguments.str("importance")?.toIntOrNull() ?: 0
                    val saved = repository.saveMemoryConsolidated(text, tags, importance)
                    // Honest rejection (BUGS.md M1): a filler or a narration about the conversation
                    // is not a fact. Saying «запомнил» here would make the model believe it holds
                    // a memory that was never written.
                    if (saved.rejected) {
                        return@withContext ToolResult(
                            true,
                            "Не записал: «${text.take(REJECT_PREVIEW_CHARS)}» — это не факт, " +
                                "а обрывок или пересказ разговора. Сформулируй утверждение о " +
                                "пользователе, его делах или договорённостях."
                        )
                    }
                    val message = if (saved.created) "Запомнил (#${saved.id})."
                    else "Обновил запись #${saved.id}."
                    // Honest: only a NEW row has an exact inverse. An update cannot be reverted
                    // (the previous text is not kept), so it is deliberately not recorded.
                    if (saved.created) {
                        undoRecorder.record(
                            toolName = "memory_save",
                            description = "Сохранена запись памяти #${saved.id}: ${text.take(UNDO_PREVIEW_CHARS)}",
                            undoKind = UndoKinds.MEMORY_DELETE,
                            payload = mapOf(UndoPayload.ID to saved.id.toString()),
                        )
                    }
                    ToolResult(true, message)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "memory_search"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val query = arguments.str("query") ?: return@withContext ToolResult(false, "Укажите query.")
                    val found = repository.searchMemoriesRanked(query, limit = MEMORY_RESULTS)
                    if (found.isEmpty()) {
                        return@withContext ToolResult(true, "Ничего не вспомнил по «$query».")
                    }
                    val header = if (found.any { it.lexicallyMatched }) ""
                    else "Прямых совпадений нет — показаны самые важные/свежие:\n"
                    ToolResult(
                        true,
                        header + found.joinToString("\n") {
                            val score = String.format(Locale.US, "%.2f", it.score)
                            "#${it.note.id} | релевантность $score | ${it.note.text}"
                        }
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "memory_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val query = arguments.str("query")
                    val tag = arguments.str("tag")
                    val kind = arguments.str("kind") ?: KIND_MEMORY
                    val sinceDays = arguments.str("sinceDays")?.toIntOrNull()?.takeIf { it > 0 }
                    val orderBy = arguments.str("orderBy")?.lowercase(Locale.ROOT) ?: ORDER_RECENT
                    val requested = arguments.str("limit")?.toIntOrNull()
                    val limit = (requested ?: MEMORY_LIST_DEFAULT).coerceIn(1, MEMORY_LIST_MAX)

                    val pool = if (kind == KIND_MEMORY) repository.listMemories(MEMORY_LIST_SCAN)
                    else repository.listNotes(kind, MEMORY_LIST_SCAN)

                    val threshold = sinceDays?.let { System.currentTimeMillis() - it * MILLIS_PER_DAY }
                    var filtered: List<NoteEntity> = pool.filter { entity ->
                        (query == null || entity.text.contains(query, ignoreCase = true)) &&
                            (tag == null || entity.tags.contains(tag, ignoreCase = true)) &&
                            (threshold == null || maxOf(entity.createdAt, entity.lastUsedAt) >= threshold)
                    }
                    if (orderBy == ORDER_IMPORTANCE) {
                        filtered = filtered.sortedWith(
                            compareByDescending<NoteEntity> { it.importance }.thenByDescending { it.id }
                        )
                    } else if (orderBy == ORDER_RECENT) {
                        filtered = filtered.sortedWith(
                            compareByDescending<NoteEntity> { maxOf(it.createdAt, it.lastUsedAt) }
                                .thenByDescending { it.id }
                        )
                    }
                    val memories = filtered.take(limit)

                    if (memories.isEmpty()) {
                        val filteredRequest = query != null || tag != null || sinceDays != null ||
                            kind != KIND_MEMORY
                        return@withContext ToolResult(
                            true,
                            if (filteredRequest) "По заданным фильтрам ничего не найдено."
                            else "Память пока пуста."
                        )
                    }
                    ToolResult(
                        true,
                        memories.joinToString("\n") {
                            "#${it.id} | важность ${it.importance} | " +
                                "${it.tags.trim().ifEmpty { "—" }} | ${it.text}"
                        }
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "memory_delete"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val id = arguments.str("id")?.toLongOrNull()
                        ?: return@withContext ToolResult(false, "Укажите id (из memory_list).")
                    val deleted = repository.deleteMemory(id)
                    // Post-check (BUG-059): the row must be gone from the memory list afterwards.
                    val stillThere = repository.listMemories(MEMORY_LIST_SCAN).any { it.id == id }
                    ToolResult(
                        success = deleted && !stillThere,
                        output = when {
                            !deleted -> "Запись #$id не найдена"
                            stillThere -> "Запись #$id всё ещё видна после удаления — НЕ подтверждено"
                            else -> "Запись #$id удалена"
                        }
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "opinion_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val text = arguments.str("text") ?: return@withContext ToolResult(false, "Укажите text.")
                    val tags = arguments.str("tags").orEmpty()
                    val confidence = arguments.str("confidence")?.toFloatOrNull()?.coerceIn(0f, 1f)
                        ?: DEFAULT_CONFIDENCE
                    val supersedesId = arguments.str("supersedesId")?.toLongOrNull()?.coerceAtLeast(0L)
                        ?: 0L
                    val id = repository.saveOpinion(text, tags, confidence, supersedesId)
                    val confidenceText = String.format(Locale.US, "%.2f", confidence)
                    val message = if (supersedesId > 0L) {
                        "Мнение #$id сохранено (уверенность $confidenceText, заменяет #$supersedesId)."
                    } else {
                        "Мнение #$id сохранено (уверенность $confidenceText)."
                    }
                    ToolResult(true, message)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "opinion_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val requested = arguments.str("limit")?.toIntOrNull()
                    val limit = (requested ?: OPINION_LIST_DEFAULT).coerceIn(1, OPINION_LIST_MAX)
                    val includeRetired = arguments.str("includeRetired")?.toBoolean() ?: false
                    val all = repository.listOpinions(OPINION_LIST_SCAN)
                    val retired = repository.supersededNoteIds()
                    val visible = if (includeRetired) all else all.filter { it.id !in retired }
                    if (visible.isEmpty()) {
                        val message = if (all.isNotEmpty() && !includeRetired) {
                            "Актуальных мнений нет (остальные вытеснены новыми)."
                        } else {
                            "Мнений нет."
                        }
                        return@withContext ToolResult(true, message)
                    }
                    val now = System.currentTimeMillis()
                    val lines = visible.take(limit).joinToString("\n") { opinion ->
                        val effective =
                            OpinionConfidence.effective(opinion.confidence, opinion.createdAt, now)
                        val confidenceText = String.format(Locale.US, "%.2f", effective)
                        val retiredMark = if (opinion.id in retired) " | вытеснено" else ""
                        "#${opinion.id} | уверенность $confidenceText$retiredMark | ${opinion.text}"
                    }
                    ToolResult(true, lines)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "todo_add"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val text = arguments.str("text") ?: return@withContext ToolResult(false, "Укажите text.")
                    val id = repository.addTodo(text)
                    undoRecorder.record(
                        toolName = "todo_add",
                        description = "Добавлено дело #$id: ${text.take(UNDO_PREVIEW_CHARS)}",
                        undoKind = UndoKinds.TODO_DELETE,
                        payload = mapOf(UndoPayload.ID to id.toString()),
                    )
                    ToolResult(true, "Задача #$id добавлена.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "todo_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val todos = repository.listTodos()
                    if (todos.isEmpty()) ToolResult(true, "Список дел пуст.")
                    else ToolResult(true, todos.joinToString("\n") {
                        "#${it.id} | ${if (it.done) "[x]" else "[ ]"} ${it.text}"
                    })
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "todo_done"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val id = arguments.str("id")?.toLongOrNull()
                        ?: return@withContext ToolResult(false, "Укажите id (из todo_list).")
                    if (repository.setTodoDone(id, true)) ToolResult(true, "Задача #$id отмечена выполненной.")
                    else ToolResult(false, "Задача #$id не найдена.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "todo_delete"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val id = arguments.str("id")?.toLongOrNull()
                        ?: return@withContext ToolResult(false, "Укажите id (из todo_list).")
                    val deleted = repository.deleteTodo(id)
                    // Post-check (BUG-059): the todo must be gone from the list afterwards.
                    val stillThere = repository.listTodos().any { it.id == id }
                    ToolResult(
                        success = deleted && !stillThere,
                        output = when {
                            !deleted -> "Задача #$id не найдена."
                            stillThere -> "Задача #$id всё ещё видна после удаления — НЕ подтверждено."
                            else -> "Задача #$id удалена."
                        }
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

}
