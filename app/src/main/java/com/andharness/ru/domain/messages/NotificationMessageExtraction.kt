package com.andharness.ru.domain.messages

import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.MessagingIndexPolicy

/**
 * Pure, Android-free mapping of a parsed notification into one or more [StoredMessage]-shaped rows.
 *
 * The Android side only has to read the notification extras/`MessagingStyle` into a list of
 * [RawMessage] triples `(text, time, sender)` and pass the conversation title; all the decisions
 * (which conversation key, whether it is a group, which single-message fallback to use, and the
 * source kind of the package) live here so they can be unit-tested on the JVM.
 *
 * Official references:
 * - `Notification.MessagingStyle` (getMessages/getConversationTitle):
 *   https://developer.android.com/reference/android/app/Notification.MessagingStyle
 * - `Notification.MessagingStyle.extractMessagingStyleFromNotification` (reads `EXTRA_MESSAGES`):
 *   https://developer.android.com/reference/android/app/Notification.MessagingStyle#extractMessagingStyleFromNotification(android.app.Notification)
 * - `Notification.EXTRA_CONVERSATION_TITLE` (set only for group conversations):
 *   https://developer.android.com/reference/android/app/Notification#EXTRA_CONVERSATION_TITLE
 * - `Notification.EXTRA_TEXT_LINES` (the InboxStyle summary-lines fallback):
 *   https://developer.android.com/reference/android/app/Notification#EXTRA_TEXT_LINES
 */
object NotificationMessageExtraction {

    /** One message as read on the Android side: full text, epoch millis, optional sender name. */
    data class RawMessage(
        val text: String,
        val at: Long,
        val sender: String?
    )

    /** One message ready to persist; [text] is the full body. */
    data class ExtractedMessage(
        val text: String,
        val at: Long,
        val sender: String?
    )

    /**
     * The derived conversation identity plus the messages. [conversationKey] is the group title when
     * present, else the sender; [isGroup] is `true` for a titled conversation or two distinct
     * participants.
     */
    data class Result(
        val conversationKey: String?,
        val isGroup: Boolean,
        val messages: List<ExtractedMessage>
    )

    /**
     * Maps the [raw] `MessagingStyle`/line messages into rows, falling back to one message built from
     * [fallbackText] (with [fallbackSender]) when [raw] is empty. Blank messages are dropped; a blank
     * fallback yields no messages at all.
     */
    fun extract(
        raw: List<RawMessage>,
        conversationTitle: String?,
        fallbackSender: String?,
        fallbackText: String?,
        fallbackAt: Long
    ): Result {
        val title = conversationTitle.clean()
        val messages = raw.mapNotNull { message ->
            val body = message.text.clean() ?: return@mapNotNull null
            ExtractedMessage(text = body, at = message.at, sender = message.sender.clean())
        }
        val effective: List<ExtractedMessage>
        val senderForKey: String?
        if (messages.isNotEmpty()) {
            effective = messages
            senderForKey = messages.firstNotNullOfOrNull { it.sender } ?: fallbackSender.clean()
        } else {
            val body = fallbackText.clean()
            val sender = fallbackSender.clean()
            effective = if (body == null) emptyList() else listOf(ExtractedMessage(body, fallbackAt, sender))
            senderForKey = sender
        }
        val distinctSenders = effective.mapNotNull { it.sender }.distinct()
        return Result(
            // No messages means nothing to hang a conversation off of, even if a sender was known.
            conversationKey = if (effective.isEmpty()) null else (title ?: senderForKey),
            isGroup = title != null || distinctSenders.size >= 2,
            messages = effective
        )
    }

    /**
     * Source kind of a notification package: an SMS client is `SMS`, a mail client `EMAIL`, everything
     * else (a messenger) `MESSENGER`. Reuses the same package sets as the person-base policy.
     */
    fun sourceTypeFor(packageName: String?): String = when (MessagingIndexPolicy.channelFor(packageName)) {
        InteractionChannel.SMS -> StoredMessageSource.SMS
        InteractionChannel.EMAIL -> StoredMessageSource.EMAIL
        else -> StoredMessageSource.MESSENGER
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
