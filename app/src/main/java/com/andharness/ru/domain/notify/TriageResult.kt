package com.andharness.ru.domain.notify


/** Result of [NotificationTriage.triage]. */
data class TriageResult(
    /** Concise one-line gist, at most [NotificationTriage.MAX_SUMMARY] characters. */
    val summary: String,
    val importance: TriageImportance,
    val category: TriageCategory,
    val reasons: List<String>,
    /** True for one-time codes / passwords: such content is NEVER memorized. */
    val secret: Boolean = false,
)
