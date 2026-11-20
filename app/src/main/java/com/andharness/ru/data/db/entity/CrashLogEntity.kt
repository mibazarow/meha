package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One recorded fatal crash (update.md §1.4). Written by
 * [com.andharness.ru.data.crash.CrashGuard] from the default uncaught-exception handler, so it must
 * stay a plain data row (no relations, no foreign keys) that can be inserted on the crashing thread.
 *
 * [stack] is TRUNCATED by [com.andharness.ru.domain.crash.CrashStackTruncator] before insertion —
 * a crash log is a bounded diagnostic store, not a raw log dump. The whole table is pruned by
 * [com.andharness.ru.domain.crash.CrashLogRepository.prune] to the last 50 rows / 30 days.
 *
 * Official references:
 * - https://developer.android.com/reference/java/lang/Thread#setDefaultUncaughtExceptionHandler(java.lang.Thread.UncaughtExceptionHandler)
 * - https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "crash_logs",
    indices = [Index("at")]
)
data class CrashLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val at: Long,
    val exceptionType: String,
    val stack: String,
    val appVersion: String,
    val deviceModel: String,
    val apiLevel: Int,
    val wasForeground: Boolean,
    val restartNumber: Int
)
