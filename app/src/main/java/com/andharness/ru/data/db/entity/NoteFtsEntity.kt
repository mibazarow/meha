package com.andharness.ru.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * FTS4-индекс по долговременной памяти/заметкам: поиск кандидатов по `MATCH`
 * вместо повторной токенизации всех строк на каждом ходу модели.
 *
 * Таблица «content-backed»: [Fts4.contentEntity] указывает на [NoteEntity], поэтому содержимое
 * хранится ТОЛЬКО в `notes`, а `notes_fts` держит лишь поисковый индекс; `rowid` FTS-таблицы равен
 * `notes.id`. Room сам создаёт триггеры синхронизации индекса при INSERT/UPDATE/DELETE в `notes`
 * (см. [com.andharness.ru.data.db.NoteFtsSchemaSql]).
 *
 * Токенизатор `unicode61` выбран осознанно: встроенный `simple` сворачивает регистр только для
 * ASCII, а память на русском «ФНС»/«фнс» без этого не находит друг друга.
 *
 * Reference: https://developer.android.com/training/data-storage/room/defining-data#fts
 * Reference: https://developer.android.com/reference/androidx/room/Fts4
 */
@Fts4(
    contentEntity = NoteEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61
)
@Entity(tableName = "notes_fts")
data class NoteFtsEntity(
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "tags") val tags: String
)
