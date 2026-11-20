package com.andharness.ru.data.message

import com.andharness.ru.domain.message.MessageChannel
import kotlinx.serialization.Serializable

/**
 * One deferred message of `schedule_message` (update.md §4.8).
 *
 * It is deliberately **outside Room** — no table and no migration number is consumed. The text is
 * kept here only until the message has been sent (the worker removes the job right before sending),
 * and the personal base still stores metadata + gist only (update.md §4.9): the pending text never
 * reaches `interactions`.
 *
 * @param target the resolved deliverable handle: a normalized phone for SMS, a handle for MESSENGER.
 * @param targetPackage the messenger app package, or `null` when unknown (SMS / generic share).
 * @param chatId the chat the user asked from, used for the delivery-report deep link.
 */
@Serializable
data class DeferredMessage(
    val id: Long,
    val personId: Long?,
    val displayName: String,
    val target: String,
    val targetPackage: String? = null,
    val channel: MessageChannel,
    val text: String,
    val scheduledAt: Long,
    val chatId: Long? = null
)
