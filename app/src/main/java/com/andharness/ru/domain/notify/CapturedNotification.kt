package com.andharness.ru.domain.notify


/**
 * One captured (and triaged) notification, as kept by [NotificationCaptureLog].
 *
 * Only the **bounded summary** is stored — never the original message body — so the digest can be
 * built without turning the capture buffer into a second copy of the user's messages.
 */
data class CapturedNotification(
    val app: String,
    val sender: String?,
    val summary: String,
    val importance: TriageImportance,
    val category: TriageCategory,
    val reasons: List<String>,
    val at: Long,
)
