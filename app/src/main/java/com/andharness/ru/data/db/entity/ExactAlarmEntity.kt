package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persisted one-shot exact alarm scheduled by `alarm_exact_set`.
 *
 * [atMs] is the trigger time in `System.currentTimeMillis()` epoch millis; [requestCode] is the
 * unique `PendingIntent` request code used to fire/cancel [com.andharness.ru.data.alarm.ExactAlarmReceiver]
 * (it is the row id, so it is stable across process restarts and reboots).
 *
 * [fired] is flipped to true by the receiver when the alarm actually fires. [chatId] is the chat the
 * alarm was created from (SPEC §20), so the notification can deep-link back to it.
 *
 * Official references:
 * - https://developer.android.com/reference/android/app/AlarmManager
 * - https://developer.android.com/reference/android/app/PendingIntent
 */
@Entity(tableName = "exact_alarms")
data class ExactAlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val atMs: Long,
    val label: String,
    val requestCode: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val fired: Boolean = false,
    val chatId: Long? = null
)
