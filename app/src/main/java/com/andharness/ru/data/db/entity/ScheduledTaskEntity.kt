package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-defined recurring/one-off task the agent runs on its own ("cron"), e.g.
 * "every morning at 08:00 send me the USD rate and the weather".
 *
 * [daysMask] is a bitmask of weekdays: bit 0 = Monday … bit 6 = Sunday; 0b1111111 (127) = daily.
 *
 * [chatId] is the chat the schedule was created from (SPEC §20); it lets the completion
 * notification deep-link to that chat. Null for schedules created before this column existed.
 *
 * [atMs], when non-null, marks a one-shot reminder at that epoch-millis instant; [hour]/[minute]/
 * [daysMask] stay populated (NOT NULL columns) but are unused for such a row. Null for ordinary
 * recurring schedules and for rows created before this column existed.
 */
@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    val daysMask: Int = 127,
    val enabled: Boolean = true,
    val lastRunAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val chatId: Long? = null,
    val atMs: Long? = null
)
