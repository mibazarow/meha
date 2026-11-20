package com.andharness.ru.domain.person


/**
 * One interaction with a [Person] (update.md §4.3): an SMS, a call, a messenger/email/chat event.
 *
 * Only metadata and a short [summary] are persisted — the message body never is (update.md §4.9).
 *
 * @param at epoch millis when the interaction happened (not when it was indexed).
 * @param externalRef the producer's own stable id of the source event (dedup key), when available.
 */
data class Interaction(
    val id: Long,
    val personId: Long,
    val channel: InteractionChannel,
    val direction: InteractionDirection,
    val at: Long,
    val title: String?,
    val summary: String?,
    val externalRef: String?,
    val sourceApp: String?
)
