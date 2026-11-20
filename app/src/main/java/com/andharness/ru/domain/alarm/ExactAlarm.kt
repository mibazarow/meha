package com.andharness.ru.domain.alarm

/**
 * Domain model of one persisted exact alarm (`exact_alarms`).
 *
 * [requestCode] is the `PendingIntent` request code used to fire/cancel the alarm; it equals the
 * row id so it is stable across restarts and reboots.
 */
data class ExactAlarm(
    val id: Long = 0,
    val atMs: Long,
    val label: String,
    val requestCode: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val fired: Boolean = false,
    val chatId: Long? = null
)
