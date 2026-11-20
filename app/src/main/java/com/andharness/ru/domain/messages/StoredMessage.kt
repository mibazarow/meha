package com.andharness.ru.domain.messages

/**
 * One captured message with its **full body**: an SMS/MMS, or a messenger/email/chat message captured
 * from a notification (persisted in `stored_messages`).
 *
 * Unlike [com.andharness.ru.domain.person.Interaction], which keeps only metadata + a bounded
 * summary, [text] is the complete message body. The lexical FTS index (`stored_message_fts`) and the
 * dense embedding store (`stored_message_vectors`) are keyed by [id].
 *
 * [sourceType] is `SMS` | `MMS` | `MESSENGER` | `EMAIL` | `CHAT`; [direction] is
 * `INCOMING` | `OUTGOING` | `UNKNOWN`. [externalRef] is the producer's stable id and is unique when
 * non-null, so re-ingesting the same source event is a no-op (or an update).
 *
 * Android-free on purpose so it is unit-testable on the JVM.
 */
data class StoredMessage(
    val id: Long,
    val sourceType: String,
    val packageName: String?,
    val conversationKey: String?,
    val personId: Long?,
    val direction: String,
    val sender: String?,
    val isGroup: Boolean,
    val at: Long,
    val text: String,
    val externalRef: String?,
    val createdAt: Long
)
