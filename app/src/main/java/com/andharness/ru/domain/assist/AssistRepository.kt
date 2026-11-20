package com.andharness.ru.domain.assist

import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.db.entity.TodoEntity

/** Notes, long-term memory and todos persisted in Room. */
interface AssistRepository {
    suspend fun listNotes(kind: String, limit: Int = 50): List<NoteEntity>
    suspend fun searchNotes(kind: String, query: String, limit: Int = 50): List<NoteEntity>
    suspend fun addNote(kind: String, text: String): Long

    /** Deletes a note by id, scoped to `kind = "note"` so a memory row can never be removed. */
    suspend fun deleteNoteById(id: Long): Boolean

    /** Newest-first bounded list of long-term memories (`kind = "memory"`). */
    suspend fun listMemories(limit: Int = 30): List<NoteEntity>

    /**
     * Bounded GC scan: the oldest-by-decay-reference memories first, so stale
     * rows stay reachable even when the table is larger than one scan. The default falls back to
     * [listMemories] for implementations that do not override it.
     */
    suspend fun listMemoriesForGc(limit: Int = 500): List<NoteEntity> = listMemories(limit)

    /** Deletes a memory by id, scoped to `kind = "memory"` so a note row can never be removed. */
    suspend fun deleteMemory(id: Long): Boolean

    /** Counts for `self_diagnostics` (see [AssistStats]). */
    suspend fun stats(): AssistStats

    /** Saves a long-term memory with lexical [tags] and an [importance] (0..2). */
    suspend fun saveMemory(text: String, tags: String = "", importance: Int = 0): Long

    /**
     * Consolidated save: a near-duplicate of an existing memory updates that
     * row (new text, unioned tags, `max` importance, `lastUsedAt = now`) instead of inserting a
     * duplicate; otherwise a new row is inserted. [MemorySaveResult.created] tells the caller which
     * happened. The default keeps the plain insert for implementations that do not consolidate.
     */
    suspend fun saveMemoryConsolidated(
        text: String,
        tags: String = "",
        importance: Int = 0
    ): MemorySaveResult {
        val id = saveMemory(text, tags, importance)
        return MemorySaveResult(
            id = id,
            created = true,
            text = text.trim(),
            tags = tags.trim(),
            importance = importance.coerceIn(0, 2)
        )
    }

    /**
     * Lexical-only recall (auto-injection into the prompt): the [MemoryHit.lexicallyMatched] subset
     * of [searchMemoriesRanked], so an unrelated turn never injects random memories. Touches the
     * returned memories' lastUsedAt.
     */
    suspend fun searchMemories(query: String, limit: Int = 10): List<NoteEntity>

    /**
     * Meaning-based recall (update.md idea #3): BM25 with synonym/inflection expansion over
     * text+tags+kind, plus importance/recency priors, best first. Never empty when the table has
     * memories: if nothing matched lexically the best by importance/recency are returned with
     * [MemoryHit.lexicallyMatched] = false. Touches only lexically matched rows' lastUsedAt.
     */
    suspend fun searchMemoriesRanked(query: String, limit: Int = 10): List<MemoryHit>

    /**
     * Кандидаты для [searchMemoriesRanked]: при большой таблице памяти — дешёвый
     * шортлист через FTS-индекс; при малой таблице, пустом FTS-результате или недоступном индексе —
     * прежний ограниченный полный скан, поэтому поведение на малых данных не меняется. Реализация по
     * умолчанию сохраняет полный скан для реализаций/фейков, которым FTS не нужен.
     */
    suspend fun searchMemoryCandidates(query: String, limit: Int = 500): List<NoteEntity> =
        listMemories(limit)

    /**
     * Saves an "opinion": a `notes` row with `kind = "opinion"`, a stance [text] and a
     * [confidence] in 0..1 that decays over time. When [supersedesId] is a non-zero id of an earlier
     * opinion, that earlier row is NOT deleted — the link is recorded, so history is kept and the
     * superseded opinion is hidden from recall / [listOpinions] default output. Default
     * implementation throws: opinions require the Room-backed repository (kept default so existing
     * test fakes compile unchanged).
     */
    suspend fun saveOpinion(
        text: String,
        tags: String = "",
        confidence: Float = 0.8f,
        supersedesId: Long = 0
    ): Long = throw UnsupportedOperationException("opinions are not supported by this repository")

    /** Newest-first opinions (`kind = "opinion"`), superseded ones included. Bounded by [limit]. */
    suspend fun listOpinions(limit: Int = 50): List<NoteEntity> = emptyList()

    /** Ids of notes retired by a newer opinion's `supersedesId` (see [saveOpinion]). */
    suspend fun supersededNoteIds(): Set<Long> = emptySet()

    suspend fun listTodos(limit: Int = 100): List<TodoEntity>
    suspend fun addTodo(text: String): Long
    suspend fun setTodoDone(id: Long, done: Boolean): Boolean
    suspend fun deleteTodo(id: Long): Boolean
}
