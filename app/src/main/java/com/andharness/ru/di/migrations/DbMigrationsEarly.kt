package com.andharness.ru.di.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.andharness.ru.data.db.PersonSchemaSql

/**
 * First half of the ordered schema-migration list: v1 -> v18. Bodies, order and SQL are frozen
 * (byte-identical to the pre-split `DatabaseModule`); [ALL] keeps the ascending order that is passed
 * to `RoomDatabase.Builder.addMigrations`.
 */
internal object DbMigrationsEarly {

    /**
     * v1 -> v2: add the reasoning-effort setting column. Existing rows get the default 'off'.
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE settings ADD COLUMN reasoningEffort TEXT NOT NULL DEFAULT 'off'"
            )
        }
    }

    /** v2 -> v3: add the scheduled-tasks ("cron") table. */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `scheduled_tasks` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`prompt` TEXT NOT NULL, " +
                    "`hour` INTEGER NOT NULL, " +
                    "`minute` INTEGER NOT NULL, " +
                    "`daysMask` INTEGER NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, " +
                    "`lastRunAt` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)"
            )
        }
    }

    /** v3 -> v4: add notes/memory and todos tables. */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `notes` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`kind` TEXT NOT NULL, " +
                    "`text` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `todos` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`text` TEXT NOT NULL, " +
                    "`done` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)"
            )
        }
    }

    /** v4 -> v5: add tags/importance/lastUsedAt to notes (long-term memory ranking). */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notes ADD COLUMN importance INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v5 -> v6: local on-device model settings. */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE settings ADD COLUMN useLocalModel INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE settings ADD COLUMN localModelPath TEXT NOT NULL DEFAULT ''")
        }
    }

    /** v6 -> v7: persisted SAF tree grants (SPEC §5.4, B1). */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `saf_grants` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`uri` TEXT NOT NULL, " +
                    "`treeDocumentId` TEXT NOT NULL, " +
                    "`displayName` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`lastCheckedAt` INTEGER NOT NULL, " +
                    "`lastOk` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_saf_grants_uri` ON `saf_grants` (`uri`)"
            )
        }
    }

    /** v7 -> v8: app-managed trash entries (TZ optional item `files_trash*`). */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `trash_entries` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`originalPath` TEXT NOT NULL, " +
                    "`trashPath` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`isDirectory` INTEGER NOT NULL, " +
                    "`sizeBytes` INTEGER NOT NULL, " +
                    "`deletedAt` INTEGER NOT NULL)"
            )
        }
    }

    /** v8 -> v9: persisted folder watches and their observed events (TZ item B4). */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `folder_watches` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`path` TEXT NOT NULL, " +
                    "`mask` INTEGER NOT NULL, " +
                    "`startAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `folder_watch_events` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`watchId` INTEGER NOT NULL, " +
                    "`event` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_folder_watch_events_watchId` " +
                    "ON `folder_watch_events` (`watchId`)"
            )
        }
    }

    /** v9 -> v10: persisted device facts and their invalidation journal (TZ §12). */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `device_facts` (" +
                    "`key` TEXT NOT NULL, " +
                    "`category` TEXT NOT NULL, " +
                    "`valueJson` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`ttlMs` INTEGER NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`sourceApi` TEXT, " +
                    "`invalidated` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`key`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `device_fact_events` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`key` TEXT NOT NULL, " +
                    "`reason` TEXT NOT NULL, " +
                    "`at` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_device_fact_events_key` " +
                    "ON `device_fact_events` (`key`)"
            )
        }
    }

    /** v10 -> v11: the chat a scheduled task was created from, for notification deep-links (TZ §20). */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN chatId INTEGER")
        }
    }

    /** v11 -> v12: persisted exact alarms (`alarm_exact_*`, DB §11). */
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `exact_alarms` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`atMs` INTEGER NOT NULL, " +
                    "`label` TEXT NOT NULL, " +
                    "`requestCode` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`fired` INTEGER NOT NULL, " +
                    "`chatId` INTEGER)"
            )
        }
    }

    /** v12 -> v13: persisted automation rules `automation_rules` (update.md §11.6). */
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `automation_rules` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, " +
                    "`triggerType` TEXT NOT NULL, " +
                    "`triggerConfig` TEXT NOT NULL, " +
                    "`actionType` TEXT NOT NULL, " +
                    "`actionConfig` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`lastRunAt` INTEGER)"
            )
        }
    }

    /**
     * v13 -> v14: crash protection (update.md §1, §6): the bounded `crash_logs` store (with an index
     * on `at`) and the single-row `restart_state` restart-guard counter.
     *
     * Sequential, non-destructive migration; nothing existing is touched.
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `crash_logs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`at` INTEGER NOT NULL, " +
                    "`exceptionType` TEXT NOT NULL, " +
                    "`stack` TEXT NOT NULL, " +
                    "`appVersion` TEXT NOT NULL, " +
                    "`deviceModel` TEXT NOT NULL, " +
                    "`apiLevel` INTEGER NOT NULL, " +
                    "`wasForeground` INTEGER NOT NULL, " +
                    "`restartNumber` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_crash_logs_at` ON `crash_logs` (`at`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `restart_state` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`restartCount` INTEGER NOT NULL, " +
                    "`windowStartAt` INTEGER NOT NULL, " +
                    "`lastRestartAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
        }
    }

    /**
     * v14 -> v15: `persons` for the unified personal base (update.md §4.3).
     * Non-destructive: creates a new table and its index only.
     */
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(PersonSchemaSql.CREATE_PERSONS_TABLE)
            db.execSQL(PersonSchemaSql.CREATE_PERSONS_DISPLAY_NAME_INDEX)
        }
    }

    /**
     * v15 -> v16: `person_identities` (normalized handles) for the unified personal base
     * (update.md §4.3). Non-destructive: creates a new table and its indices only.
     */
    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(PersonSchemaSql.CREATE_PERSON_IDENTITIES_TABLE)
            db.execSQL(PersonSchemaSql.CREATE_PERSON_IDENTITIES_UNIQUE_INDEX)
            db.execSQL(PersonSchemaSql.CREATE_PERSON_IDENTITIES_PERSON_INDEX)
        }
    }

    /**
     * v16 -> v17: `interactions` (metadata + short summary only) for the unified personal base
     * (update.md §4.3). Non-destructive: creates a new table and its indices only.
     */
    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(PersonSchemaSql.CREATE_INTERACTIONS_TABLE)
            db.execSQL(PersonSchemaSql.CREATE_INTERACTIONS_PERSON_AT_INDEX)
            db.execSQL(PersonSchemaSql.CREATE_INTERACTIONS_AT_INDEX)
        }
    }

    /**
     * v17 -> v18: `person_aliases` for the unified personal base (update.md §4.3).
     * Non-destructive: creates a new table and its indices only.
     *
     * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(PersonSchemaSql.CREATE_PERSON_ALIASES_TABLE)
            db.execSQL(PersonSchemaSql.CREATE_PERSON_ALIASES_UNIQUE_INDEX)
            db.execSQL(PersonSchemaSql.CREATE_PERSON_ALIASES_ALIAS_INDEX)
        }
    }

    /** Ascending (1 -> 18) migration list; order is part of the frozen contract. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18
    )
}
