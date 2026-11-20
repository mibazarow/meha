package com.andharness.ru.domain.crash

/** One recorded fatal crash (update.md §1.4). Mirrors `crash_logs` (Room v14). */
data class CrashLog(
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
