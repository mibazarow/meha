package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.db.entity.TodoEntity

@Dao
interface AssistDao {
    // ---- notes / memory ----
    @Query("SELECT * FROM notes WHERE kind = :kind ORDER BY id DESC LIMIT :limit")
    suspend fun listNotes(kind: String, limit: Int): List<NoteEntity>

    @Query(
        "SELECT * FROM notes WHERE kind = :kind " +
            "ORDER BY CASE WHEN lastUsedAt > 0 THEN lastUsedAt ELSE createdAt END ASC, id ASC LIMIT :limit"
    )
    suspend fun listNotesOldestByUse(kind: String, limit: Int): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE kind = :kind AND text LIKE '%' || :query || '%' ORDER BY id DESC LIMIT :limit")
    suspend fun searchNotes(kind: String, query: String, limit: Int): List<NoteEntity>

    /** Bounded fetch of the given note ids of a single [kind] (e.g. graph-expanded memory ids). */
    @Query("SELECT * FROM notes WHERE kind = :kind AND id IN (:ids)")
    suspend fun listNotesByIds(kind: String, ids: List<Long>): List<NoteEntity>

    @Insert
    suspend fun insertNote(entity: NoteEntity): Long

    /**
     * Records a recall: bumps both `lastUsedAt` (recency ranking) and `accessCount`
     * (recall-frequency prior) of the note.
     */
    @Query("UPDATE notes SET lastUsedAt = :timestamp, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun touchNote(id: Long, timestamp: Long)

    @Query(
        "UPDATE notes SET text = :text, tags = :tags, importance = :importance, lastUsedAt = :lastUsedAt " +
            "WHERE id = :id AND kind = 'memory'"
    )
    suspend fun updateMemory(id: Long, text: String, tags: String, importance: Int, lastUsedAt: Long): Int

    /** Newest-first opinions (`kind = "opinion"`), bounded by [limit]. */
    @Query("SELECT * FROM notes WHERE kind = 'opinion' ORDER BY id DESC LIMIT :limit")
    suspend fun listOpinions(limit: Int): List<NoteEntity>

    /**
     * Ids of all notes that some other note supersedes (`supersedesId != 0`). A row whose id is in
     * this list is RETIRED: kept for history but hidden from recall / `opinion_list` by default.
     */
    @Query("SELECT supersedesId FROM notes WHERE supersedesId != 0")
    suspend fun supersededNoteIds(): List<Long>

    @Query("DELETE FROM notes WHERE id = :id AND kind = :kind")
    suspend fun deleteNoteById(id: Long, kind: String): Int

    @Query("SELECT COUNT(*) FROM notes WHERE kind = :kind")
    suspend fun countByKind(kind: String): Int

    @Query("SELECT importance, COUNT(*) AS count FROM notes WHERE kind = :kind GROUP BY importance")
    suspend fun countByImportance(kind: String): List<ImportanceCount>

    // ---- todos ----
    @Query("SELECT * FROM todos ORDER BY done ASC, id DESC LIMIT :limit")
    suspend fun listTodos(limit: Int): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun getTodo(id: Long): TodoEntity?

    @Insert
    suspend fun insertTodo(entity: TodoEntity): Long

    @Query("UPDATE todos SET done = :done WHERE id = :id")
    suspend fun setTodoDone(id: Long, done: Boolean)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun deleteTodo(id: Long): Int
}
