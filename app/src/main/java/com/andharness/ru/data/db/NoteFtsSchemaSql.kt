package com.andharness.ru.data.db

/**
 * Сырой SQL миграции v27 -> v28: FTS4-индекс `notes_fts` по [com.andharness.ru.data.db.entity.NoteEntity]
 * а также триггеры синхронизации индекса, которые Room генерирует для content-backed
 * FTS-сущностей.
 *
 * Строки вынесены в константы (как [RagChunkSchemaSql]), чтобы JVM-тест сверил их с реальной
 * схемой `AppDatabase_Impl` без устройства. Порядок опций виртуальной таблицы и имена/тела
 * триггеров должны совпадать с генерацией Room 2.6.1 побайтово, иначе runtime-валидация Room
 * (`Migration didn't properly handle`) упадёт при открытии БД.
 *
 * Неразрушающе: создаётся только новая виртуальная таблица и триггеры; строки `notes` не трогаются.
 *
 * Reference: https://developer.android.com/training/data-storage/room/defining-data#fts
 * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
 */
object NoteFtsSchemaSql {

    const val CREATE_NOTES_FTS_TABLE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS `notes_fts` USING FTS4(" +
            "`text` TEXT NOT NULL, `tags` TEXT NOT NULL, " +
            "tokenize=unicode61, content=`notes`)"

    const val CREATE_TRIGGER_BEFORE_UPDATE =
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_notes_fts_BEFORE_UPDATE " +
            "BEFORE UPDATE ON `notes` BEGIN DELETE FROM `notes_fts` " +
            "WHERE `docid`=OLD.`rowid`; END"

    const val CREATE_TRIGGER_BEFORE_DELETE =
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_notes_fts_BEFORE_DELETE " +
            "BEFORE DELETE ON `notes` BEGIN DELETE FROM `notes_fts` " +
            "WHERE `docid`=OLD.`rowid`; END"

    const val CREATE_TRIGGER_AFTER_UPDATE =
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_notes_fts_AFTER_UPDATE " +
            "AFTER UPDATE ON `notes` BEGIN INSERT INTO `notes_fts`(`docid`, `text`, `tags`) " +
            "VALUES (NEW.`rowid`, NEW.`text`, NEW.`tags`); END"

    const val CREATE_TRIGGER_AFTER_INSERT =
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_notes_fts_AFTER_INSERT " +
            "AFTER INSERT ON `notes` BEGIN INSERT INTO `notes_fts`(`docid`, `text`, `tags`) " +
            "VALUES (NEW.`rowid`, NEW.`text`, NEW.`tags`); END"

    /**
     * BACKFILL: перестроить FTS-индекс из уже существующих строк `notes` (content-backed FTS не
     * индексирует содержимое автоматически). Спецкоманда FTS4 `rebuild` идемпотентна и не меняет
     * сами заметки.
     *
     * Reference: https://www.sqlite.org/fts3.html#the_rebuild_command
     */
    const val REBUILD_INDEX =
        "INSERT INTO `notes_fts`(`notes_fts`) VALUES('rebuild')"

    /** Все DDL-стейтменты миграции, в порядке выполнения (тест-хелпер). */
    val all: List<String> = listOf(
        CREATE_NOTES_FTS_TABLE,
        CREATE_TRIGGER_BEFORE_UPDATE,
        CREATE_TRIGGER_BEFORE_DELETE,
        CREATE_TRIGGER_AFTER_UPDATE,
        CREATE_TRIGGER_AFTER_INSERT,
    )
}
