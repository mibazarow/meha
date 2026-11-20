package com.andharness.ru.data.db

/**
 * Сырой SQL FTS4-индекса `stored_message_fts` по
 * [com.andharness.ru.data.db.entity.StoredMessageEntity], а также триггеров синхронизации индекса,
 * которые Room генерирует для content-backed FTS-сущностей (клон
 * [TranscriptFtsSchemaSql]).
 *
 * Строки вынесены в константы, чтобы JVM-тест сверил их с реальной схемой `AppDatabase_Impl` без
 * устройства. Порядок опций виртуальной таблицы и имена/тела триггеров должны совпадать с
 * генерацией Room побайтово, иначе runtime-валидация Room
 * (`Migration didn't properly handle`) упадёт при открытии БД.
 *
 * Неразрушающе: создаётся только новая виртуальная таблица и триггеры; строки
 * `stored_messages` не трогаются.
 *
 * Reference: https://developer.android.com/training/data-storage/room/defining-data#fts
 * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
 */
object StoredMessageFtsSchemaSql {

    const val CREATE_STORED_MESSAGE_FTS_TABLE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS `stored_message_fts` USING FTS4(" +
            "`text` TEXT NOT NULL, " +
            "tokenize=unicode61, content=`stored_messages`)"

    const val CREATE_TRIGGER_BEFORE_UPDATE =
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_stored_message_fts_BEFORE_UPDATE " +
            "BEFORE UPDATE ON `stored_messages` BEGIN DELETE FROM `stored_message_fts` " +
            "WHERE `docid`=OLD.`rowid`; END"

    const val CREATE_TRIGGER_BEFORE_DELETE =
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_stored_message_fts_BEFORE_DELETE " +
            "BEFORE DELETE ON `stored_messages` BEGIN DELETE FROM `stored_message_fts` " +
            "WHERE `docid`=OLD.`rowid`; END"

    const val CREATE_TRIGGER_AFTER_UPDATE =
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_stored_message_fts_AFTER_UPDATE " +
            "AFTER UPDATE ON `stored_messages` BEGIN INSERT INTO `stored_message_fts`(`docid`, `text`) " +
            "VALUES (NEW.`rowid`, NEW.`text`); END"

    const val CREATE_TRIGGER_AFTER_INSERT =
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_stored_message_fts_AFTER_INSERT " +
            "AFTER INSERT ON `stored_messages` BEGIN INSERT INTO `stored_message_fts`(`docid`, `text`) " +
            "VALUES (NEW.`rowid`, NEW.`text`); END"

    /**
     * BACKFILL: перестроить FTS-индекс из уже существующих строк `stored_messages` (content-backed
     * FTS не индексирует содержимое автоматически). Спецкоманда FTS4 `rebuild` идемпотентна и не
     * меняет сами сообщения.
     *
     * Reference: https://www.sqlite.org/fts3.html#the_rebuild_command
     */
    const val REBUILD_INDEX =
        "INSERT INTO `stored_message_fts`(`stored_message_fts`) VALUES('rebuild')"

    /** Все DDL-стейтменты FTS-индекса, в порядке выполнения (тест-хелпер). */
    val all: List<String> = listOf(
        CREATE_STORED_MESSAGE_FTS_TABLE,
        CREATE_TRIGGER_BEFORE_UPDATE,
        CREATE_TRIGGER_BEFORE_DELETE,
        CREATE_TRIGGER_AFTER_UPDATE,
        CREATE_TRIGGER_AFTER_INSERT,
    )
}
