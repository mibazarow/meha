package com.andharness.ru.di.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.andharness.ru.data.db.AutomationEventSchemaSql
import com.andharness.ru.data.db.ChatMessageVectorSchemaSql
import com.andharness.ru.data.db.CommunicationsSchemaSql
import com.andharness.ru.data.db.DeviceIndexVectorsSchemaSql
import com.andharness.ru.data.db.LlmProviderSchemaSql
import com.andharness.ru.data.db.LlmUsageSchemaSql
import com.andharness.ru.data.db.MemoryEntitySchemaSql
import com.andharness.ru.data.db.MemoryPersonSchemaSql
import com.andharness.ru.data.db.MemoryVectorSchemaSql
import com.andharness.ru.data.db.NoteFtsSchemaSql
import com.andharness.ru.data.db.RagChunkSchemaSql
import com.andharness.ru.data.db.ScheduledTaskSchemaSql
import com.andharness.ru.data.db.SemanticSearchSchemaSql
import com.andharness.ru.data.db.SshSchemaSql
import com.andharness.ru.data.db.StoredMessageFtsSchemaSql
import com.andharness.ru.data.db.StoredMessageSchemaSql
import com.andharness.ru.data.db.ToolAuditSchemaSql
import com.andharness.ru.data.db.TranscriptFtsSchemaSql

/**
 * Second half of the ordered schema-migration list: v18 -> v39. Bodies, order and SQL are frozen
 * (byte-identical to the pre-split `DatabaseModule`); [ALL] keeps the ascending order that is passed
 * to `RoomDatabase.Builder.addMigrations`.
 */
internal object DbMigrationsLate {

    /**
     * v18 -> v19: the SSH server registry `ssh_servers` (update.md §5.2, §6 step 6).
     * Non-destructive: creates a new table and its unique index on `name` only.
     * No secret is stored in Room — passwords/passphrases live in EncryptedSharedPreferences and
     * private keys are referenced by a SAF URI only (update.md §5.4).
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(SshSchemaSql.CREATE_SSH_SERVERS_TABLE)
            db.execSQL(SshSchemaSql.CREATE_SSH_SERVERS_NAME_UNIQUE_INDEX)
        }
    }

    /**
     * v19 -> v20: an index on `device_fact_events.at`, the column the newest-first invalidation
     * journal is ordered by (update.md §12.3). Non-destructive: creates the index only, existing
     * rows are untouched. The name must match Room's generated schema
     * (`index_device_fact_events_at`).
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_device_fact_events_at` " +
                    "ON `device_fact_events` (`at`)"
            )
        }
    }

    /**
     * v20 -> v21: the append-only tool-call audit log `tool_audit` (update.md «Идеи следующих волн»,
     * idea #6) plus its index on `at`. Non-destructive: creates a new table and an index only.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ToolAuditSchemaSql.all.forEach { db.execSQL(it) }
        }
    }

    /**
     * v21 -> v22: the token/cost ledger `llm_usage` (update.md «Идеи следующих волн», idea #2) plus
     * its index on `at`, and the opt-in model-router / price columns on `settings`.
     *
     * Non-destructive: creates a new table + index and ADD COLUMNs with defaults only; existing rows
     * (chats, messages, settings) are untouched.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            LlmUsageSchemaSql.all.forEach { db.execSQL(it) }
            db.execSQL("ALTER TABLE settings ADD COLUMN secondaryModel TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE settings ADD COLUMN routingEnabled INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE settings ADD COLUMN primaryInputPricePerMillion REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE settings ADD COLUMN primaryOutputPricePerMillion REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE settings ADD COLUMN secondaryInputPricePerMillion REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE settings ADD COLUMN secondaryOutputPricePerMillion REAL NOT NULL DEFAULT 0")
        }
    }

    /**
     * v22 -> v23: the outgoing-request privacy mode (update.md idea #5) — OFF | SECRETS | SECRETS_PII.
     * Non-destructive: ADD COLUMN with the default only; existing rows keep their conversation and
     * start in the SECRETS mode.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE settings ADD COLUMN privacyMode TEXT NOT NULL DEFAULT 'SECRETS'")
        }
    }

    /**
     * v23 -> v24: the local RAG chunk index `rag_chunks` (update.md idea #4) plus its index on
     * `sourcePath`. Non-destructive: creates a new table and an index only; existing chats, messages,
     * memories and settings are untouched.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            RagChunkSchemaSql.all.forEach { db.execSQL(it) }
        }
    }

    /**
     * v24 -> v25: the OCR script setting for the offline recognizer (`CYRILLIC` = `rus+eng`,
     * `LATIN` = `eng`). Non-destructive: ADD COLUMN with the default only; existing rows keep their
     * conversation and switch to Cyrillic recognition, which is the honest default on this product
     * (ML Kit has no Cyrillic model).
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE settings ADD COLUMN ocrScript TEXT NOT NULL DEFAULT 'CYRILLIC'")
        }
    }

    /**
     * v25 -> v26: the one-shot reminder instant `scheduled_tasks.atMs` (non-null ⇒ the task fires
     * once at that epoch-millis instant instead of recurring). Non-destructive: ADD COLUMN only;
     * existing rows keep `atMs = NULL` and stay recurring, hour/minute/daysMask are untouched.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(ScheduledTaskSchemaSql.ADD_AT_MS_COLUMN)
        }
    }

    /**
     * v26 -> v27: the persistent firing-event log `automation_events` plus its index
     * on `createdAt`. Non-destructive: creates a new table and an index only; existing
     * `automation_rules` and everything else are untouched.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            AutomationEventSchemaSql.all.forEach { db.execSQL(it) }
        }
    }

    /**
     * v27 -> v28: FTS4-индекс `notes_fts` по долговременной памяти/заметкам плюс
     * триггеры синхронизации индекса, которые Room генерирует для content-backed FTS-сущности.
     * После создания таблицы индекс ПЕРЕСБИРАЕТСЯ из существующих строк `notes` (спецкоманда FTS4
     * `rebuild`) — иначе поиск не увидел бы уже сохранённую память.
     *
     * Неразрушающе: DDL создаёт виртуальную таблицу и триггеры, `rebuild` только читает `notes`;
     * строки заметок/памяти не изменяются и не удаляются.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     * Reference: https://developer.android.com/training/data-storage/room/defining-data#fts
     */
    private val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            NoteFtsSchemaSql.all.forEach { db.execSQL(it) }
            db.execSQL(NoteFtsSchemaSql.REBUILD_INDEX)
        }
    }

    /**
     * v28 -> v29: плотные эмбеддинги долговременной памяти `memory_vectors` плюс
     * необязательный столбец `settings.embeddingModel`, включающий семантический поиск.
     *
     * Неразрушающе: создаётся новая таблица и индекс, а к `settings` добавляется столбец со
     * значением по умолчанию '' (семантика выключена) — строки заметок/памяти и настройки не
     * изменяются и не удаляются.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MemoryVectorSchemaSql.all.forEach { db.execSQL(it) }
        }
    }

    /**
     * v29 -> v30: включает семантический поиск по памяти на встроенной офлайн-модели
     * эмбеддингов. Неразрушающе: к `settings` добавляется булев столбец со значением по умолчанию
     * 0 (семантика выключена) — строки заметок/памяти и настройки не изменяются и не удаляются.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SemanticSearchSchemaSql.all.forEach { db.execSQL(it) }
        }
    }

    /**
     * v30 -> v31: таблица связей памяти и людей `memory_persons` (составной ключ
     * `noteId` + `personId`, вес и время создания) плюс индекс на `personId`.
     *
     * Неразрушающе: создаётся новая таблица и индекс; строки заметок/памяти, люди и настройки не
     * изменяются и не удаляются.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MemoryPersonSchemaSql.all.forEach { db.execSQL(it) }
        }
    }

    /**
     * v31 -> v32: счётчик обращений к памяти `notes.accessCount` — частота recall, используемая
     * как небольшой ранжирующий приоритет. Неразрушающе: ADD COLUMN со значением по умолчанию 0;
     * существующие строки заметок/памяти сохраняются и стартуют с 0 (ещё ни разу не вспоминались),
     * все прочие столбцы не изменяются.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN accessCount INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v32 -> v33: разово включает семантический поиск по памяти для уже существующих установок.
     * Теперь семантика включена по умолчанию, поэтому строки `settings`, которые получили старый
     * DEFAULT 0 при миграции 29 -> 30, переключаются на 1. Это намеренно не пытается вспомнить
     * прежний отказ пользователя: у столбца никогда не было маркера «пользователь настроил вручную».
     * Кому семантика не нужна — выключает переключатель в настройках.
     *
     * Неразрушающе: обновляется только булев столбец `settings.semanticSearchEnabled`; строки
     * заметок/памяти, люди и прочие настройки не изменяются и не удаляются.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE settings SET semanticSearchEnabled = 1")
        }
    }

    /**
     * v33 -> v34: «мнения» поверх долговременной памяти — уверенность `notes.confidence` (0..1),
     * которая со временем экспоненциально затухает, и ссылка `notes.supersedesId` на мнение,
     * которое эта запись вытесняет (0 = никого; сама вытесненная строка не удаляется — история
     * сохраняется). Неразрушающе: только два `ADD COLUMN` со значениями по умолчанию; существующие
     * заметки/память получают confidence = 1.0 (без штрафа) и supersedesId = 0, все прочие столбцы
     * не изменяются и не удаляются.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
            db.execSQL("ALTER TABLE notes ADD COLUMN supersedesId INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v34 -> v35: граф обобщённых сущностей — узлы `entities` (topic/project/place/other, с
     * уникальным `normalizedName`) и связи `memory_entities` (составной ключ `noteId` + `entityId`,
     * вес и время создания) плюс индекс на `entityId`. Повторяет граф `memory ↔ person` и служит
     * для графового расширения recall по темам/проектам/местам.
     *
     * Неразрушающе: создаются только новые таблицы и их индексы; строки заметок/памяти, люди,
     * мнения и настройки не изменяются и не удаляются.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MemoryEntitySchemaSql.all.forEach { db.execSQL(it) }
        }
    }

    /**
     * v35 -> v36: «Коммуникации» — записи `recordings`, сегменты транскрипта
     * `transcript_segments`, плотные эмбеддинги сегментов `transcript_vectors`, реестр голосов
     * `speakers` и их сэмплы `speaker_voice_embeddings`, а также FTS4-индекс `transcript_fts` с
     * триггерами синхронизации. `transcript_segments` создаётся в этой же миграции и на момент её
     * выполнения пуст, поэтому отдельный backfill `rebuild` не нужен — новые сегменты индексируются
     * триггерами `AFTER INSERT`.
     *
     * Неразрушающе: создаются только новые таблицы, индексы, виртуальная FTS-таблица и триггеры;
     * строки заметок/памяти, люди, настройки и все прочие данные не изменяются и не удаляются.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     * Reference: https://developer.android.com/training/data-storage/room/defining-data#fts
     */
    private val MIGRATION_35_36 = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            CommunicationsSchemaSql.all.forEach { db.execSQL(it) }
            TranscriptFtsSchemaSql.all.forEach { db.execSQL(it) }
        }
    }

    /**
     * v36 -> v37: the chat pin marker `chats.pinnedAt` (0 = not pinned; epoch millis when pinned),
     * which lets the chat list float pinned chats to the top without touching `updatedAt`.
     *
     * Non-destructive: a single `ADD COLUMN` with a default; existing chats keep their messages,
     * titles and `updatedAt` and start unpinned (pinnedAt = 0). `chat_recall`'s "most recent" meaning
     * is preserved by a dedicated `ORDER BY updatedAt DESC LIMIT 1` query, not the list ordering.
     *
     * The `ALTER TABLE ... ADD COLUMN` form is the documented way to add a column manually
     * (https://developer.android.com/training/data-storage/room/migrating-db-versions).
     */
    private val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN pinnedAt INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v37 -> v38: the full-text messages store — `stored_messages` (full message bodies + metadata),
     * the content-backed FTS4 index `stored_message_fts` with its index-sync triggers, and the dense
     * embedding store `stored_message_vectors`. `stored_messages` is created empty in this migration,
     * so no `rebuild` backfill is needed — new rows are indexed by the `AFTER INSERT` trigger.
     *
     * Non-destructive: creates only new tables, indices, the virtual FTS table and its triggers;
     * existing chats, messages, communications data and all other rows are untouched.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     * Reference: https://developer.android.com/training/data-storage/room/defining-data#fts
     */
    private val MIGRATION_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            StoredMessageSchemaSql.all.forEach { db.execSQL(it) }
            StoredMessageFtsSchemaSql.all.forEach { db.execSQL(it) }
        }
    }

    /**
     * v38 -> v39: the on-device embedding index for the app's OWN chat messages —
     * `chat_message_vectors` (one little-endian float32 `BLOB` per `user`/`assistant` message, keyed
     * by `messages.id`, with a model tag) plus its index on `model`. Mirrors the
     * `stored_message_vectors` half of v37 -> v38 so chat transcripts can be searched by meaning the
     * same way captured messages already are.
     *
     * Non-destructive: creates only a new table and its index; existing chats, messages and every
     * other row are untouched. The plain `CREATE TABLE`/`CREATE INDEX` DDL is SQLCipher-compatible.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_38_39 = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ChatMessageVectorSchemaSql.all.forEach { db.execSQL(it) }
        }
    }

    /**
     * v39 -> v40: the whole-device semantic index `device_index_vectors` (image embeddings and
     * document text chunks in the shared 768-d EmbeddingGemma 2 space) plus its unique
     * (`sourceUri`, `chunkIndex`) and `sourceUri` / `kind` indices.
     *
     * Non-destructive: creates only a new table and its indices; existing chats, messages, memories,
     * captured messages and every other row are untouched. The plain `CREATE TABLE`/`CREATE INDEX`
     * DDL is SQLCipher-compatible.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_39_40 = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DeviceIndexVectorsSchemaSql.all.forEach { db.execSQL(it) }
        }
    }

    /**
     * v40 -> v41: the optional structured `messages.payload` column (opaque JSON) that lets a `tool`
     * row carry a renderable attachment — today the `device_search` result cards (photo thumbnails /
     * document icons / fallback links) shown as a rich preview in the chat.
     *
     * Non-destructive: a single nullable `ADD COLUMN` (default NULL); existing chats, messages and
     * every other row are untouched, and every existing message reads back with `payload = null`.
     * The `ALTER TABLE ... ADD COLUMN` form is the documented way to add a column manually
     * (https://developer.android.com/training/data-storage/room/migrating-db-versions).
     */
    private val MIGRATION_40_41 = object : Migration(40, 41) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN payload TEXT")
        }
    }

    /**
     * v41 -> v42: saved LLM provider profiles. Creates the `llm_providers` table and adds the
     * nullable `settings.activeProviderId` column that marks the single active profile; the
     * active profile's endpoint/model are mirrored into the existing `settings` columns by the
     * store, so chats, tools and the catalog keep reading one effective configuration.
     *
     * Non-destructive: one new table plus a nullable `ADD COLUMN` (default NULL); every existing
     * chat, message, memory and settings value is untouched. The store seeds the first profile
     * from the pre-migration `settings` row on first read.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_41_42 = object : Migration(41, 42) {
        override fun migrate(db: SupportSQLiteDatabase) {
            LlmProviderSchemaSql.all.forEach { db.execSQL(it) }
            db.execSQL("ALTER TABLE settings ADD COLUMN activeProviderId INTEGER")
        }
    }

    /** Ascending (18 -> 42) migration list; order is part of the frozen contract. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33,
        MIGRATION_33_34,
        MIGRATION_34_35,
        MIGRATION_35_36,
        MIGRATION_36_37,
        MIGRATION_37_38,
        MIGRATION_38_39,
        MIGRATION_39_40,
        MIGRATION_40_41,
        MIGRATION_41_42
    )
}
