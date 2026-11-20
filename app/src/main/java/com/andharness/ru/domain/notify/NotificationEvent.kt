package com.andharness.ru.domain.notify


/**
 * One notification (or SMS) offered to the triage, already reduced to plain text by the caller.
 *
 * [NotificationEvent.text] is transient: it is only used to compute the summary/importance and is
 * never persisted (the capture log keeps the bounded summary only).
 */
data class NotificationEvent(
    val app: String,
    val sender: String? = null,
    val title: String = "",
    val text: String = "",
    val at: Long = 0L,
    val isMissedCall: Boolean = false,
    val isGroupOrBulk: Boolean = false,
    val senderKnown: Boolean = false,
    val repeatCount: Int = 1,
)
