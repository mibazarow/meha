package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.andharness.ru.data.db.entity.NoteEntity

/**
 * Дешёвый предварительный отбор кандидатов памяти через FTS4-индекс `notes_fts`.
 *
 * Отдельный DAO, а не методы в [AssistDao], чтобы существующие реализации/фейки [AssistDao] не
 * менялись: полносканирующий путь остаётся ровно тем же, а FTS используется только когда таблица
 * памяти большая (см. `MemoryCandidateSelector`).
 */
@Dao
interface NoteFtsDao {

    /**
     * Идентификаторы заметок памяти, чей текст/теги совпали с [matchQuery] (выражение `MATCH`,
     * построенное `MemoryFtsQuery`). FTS4 не имеет встроенного `rank()` (это только FTS5), поэтому
     * порядок детерминированный — по `rowid`; точное ранжирование всё равно делает `MemoryRanker`.
     *
     * Reference: https://www.sqlite.org/fts3.html
     */
    @Query(
        "SELECT rowid FROM `notes_fts` " +
            "WHERE `notes_fts` MATCH :matchQuery ORDER BY rowid LIMIT :limit"
    )
    suspend fun searchCandidateIds(matchQuery: String, limit: Int): List<Long>

    /** Полные строки памяти по [ids] — только для отобранных FTS кандидатов. */
    @Query("SELECT * FROM notes WHERE kind = :kind AND id IN (:ids)")
    suspend fun listNotesByIds(kind: String, ids: List<Long>): List<NoteEntity>
}
