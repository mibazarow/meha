package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Дешёвый предварительный отбор кандидатов сообщений через FTS4-индекс `stored_message_fts`
 * (клон [TranscriptFtsDao]).
 *
 * Отдельный DAO, чтобы полносканирующий путь оставался неизменным, а FTS использовался только на
 * больших объёмах.
 */
@Dao
interface StoredMessageFtsDao {

    /**
     * Идентификаторы сообщений, чей `text` совпал с [matchQuery] (выражение `MATCH`, построенное
     * вызывающим кодом). FTS4 не имеет встроенного `rank()` (это только FTS5), поэтому порядок
     * детерминированный — по `rowid`.
     *
     * Reference: https://www.sqlite.org/fts3.html
     */
    @Query(
        "SELECT rowid FROM `stored_message_fts` " +
            "WHERE `stored_message_fts` MATCH :matchQuery ORDER BY rowid LIMIT :limit"
    )
    suspend fun searchCandidateIds(matchQuery: String, limit: Int): List<Long>
}
