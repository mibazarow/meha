package com.andharness.ru.di

import androidx.room.migration.Migration
import com.andharness.ru.di.migrations.DbMigrationsEarly
import com.andharness.ru.di.migrations.DbMigrationsLate

/**
 * The single ordered schema-migration list for [com.andharness.ru.data.db.AppDatabase], split into
 * two version ranges for readability. The order of [ALL] (v1 -> v35, ascending) and every migration
 * body are frozen: [DatabaseModule] passes exactly this array to `addMigrations`.
 */
internal object DbMigrations {

    /** Ascending (1 -> 35) migration list; order is part of the frozen contract. */
    val ALL: Array<Migration> = arrayOf(
        *DbMigrationsEarly.ALL,
        *DbMigrationsLate.ALL
    )
}
