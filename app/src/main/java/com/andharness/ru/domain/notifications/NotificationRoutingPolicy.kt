package com.andharness.ru.domain.notifications

import com.andharness.ru.domain.person.IdentityNormalizer
import com.andharness.ru.domain.person.IdentityType
import com.andharness.ru.domain.person.MessagingIndexPolicy

/**
 * Pure routing decisions of the notification relay ingest
 * (`service/NotificationRelayService`), split out so they are unit-testable on the JVM.
 *
 * The service keeps everything that touches Android types (`StatusBarNotification`, `Notification`
 * extras, `Bundle`, repositories); everything here depends only on primitives (package name, title,
 * conversation key, timestamps, flags). Behaviour is identical to the pre-extraction service code:
 *
 * - a notification with a blank title is never indexed into the personal base;
 * - the identity a notification is filed under is the extracted conversation key when present,
 *   otherwise the title; an e-mail-looking handle is global (no owning app), a messenger handle is
 *   scoped to the posting app package;
 * - the dedup-key time basis is the message time only when the source carried real per-message
 *   timestamps, otherwise a fixed `0` so a re-post with a new `postTime` does not duplicate rows;
 * - the stored row sender falls back to the conversation key when the message has no sender.
 *
 * The opt-in + package allow-list gate itself stays in [MessagingIndexPolicy.isEligible] and the
 * message/conversation mapping in `NotificationMessageExtraction`; both are already pure.
 */
object NotificationRoutingPolicy {

    /** Identity the notification is filed under: value, type and owning app package. */
    data class Identity(
        val type: IdentityType,
        val value: String,
        val appPackage: String,
    )

    /** A notification with a blank [title] carries no gist identity and is never indexed. */
    fun hasIndexableTitle(title: String): Boolean = title.isNotBlank()

    /**
     * Identity of the notification party: [conversationKey] when the extraction derived one, else
     * [title]. An e-mail handle is global ([IdentityNormalizer.NO_PACKAGE]); a messenger handle is
     * scoped to the posting [packageName].
     */
    fun identityFor(conversationKey: String?, title: String, packageName: String): Identity {
        val value = conversationKey ?: title
        val type = MessagingIndexPolicy.identityTypeFor(value)
        val appPackage = if (type == IdentityType.MESSENGER) packageName else IdentityNormalizer.NO_PACKAGE
        return Identity(type = type, value = value, appPackage = appPackage)
    }

    /**
     * Time basis of the per-message dedup key: the real [messageAt] only when the source carried
     * per-message timestamps; otherwise `0L` so a re-post at a new time maps to the same key.
     */
    fun dedupAt(hasTimestamps: Boolean, messageAt: Long): Long = if (hasTimestamps) messageAt else 0L

    /** Stored-row sender: the message's own [messageSender], else the [conversationKey]. */
    fun senderFor(messageSender: String?, conversationKey: String?): String? = messageSender ?: conversationKey
}
