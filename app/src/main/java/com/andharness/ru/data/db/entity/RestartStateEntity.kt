package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row persistence of the crash-restart loop guard (update.md §1.2): the number of restarts
 * inside the current rolling window, when that window started, and when the last restart happened.
 *
 * The row is ALWAYS `id = 1` ([SINGLETON_ID]) — [com.andharness.ru.domain.crash.RestartStateRepository]
 * upserts it, and [com.andharness.ru.data.crash.RestartPolicy] (pure) owns the actual counting rules.
 */
@Entity(tableName = "restart_state")
data class RestartStateEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val restartCount: Int = 0,
    val windowStartAt: Long = 0L,
    val lastRestartAt: Long = 0L
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
