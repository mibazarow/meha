package com.andharness.ru.data.diagnostics


/**
 * One historical process exit as reported by the system, already mapped to a human label.
 * `pssKb`/`rssKb` are negative when the platform did not provide them.
 */
data class ProcessExitInfo(
    val timestampMs: Long,
    val reasonCode: Int,
    val reasonLabel: String,
    val description: String?,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val processName: String?
)
