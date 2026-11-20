package com.andharness.ru.service

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.andharness.ru.data.automation.AutomationEventBus
import com.andharness.ru.data.notify.notificationTriageEntryPoint
import com.andharness.ru.data.person.PersonRef
import com.andharness.ru.data.person.personIngestEntryPoint
import com.andharness.ru.domain.messages.MessageExternalRefs
import com.andharness.ru.domain.messages.NotificationMessageExtraction
import com.andharness.ru.domain.messages.StoredMessage
import com.andharness.ru.domain.messages.StoredMessageDirection
import com.andharness.ru.domain.messages.StoredMessagesRepository
import com.andharness.ru.domain.notifications.NotificationRoutingPolicy
import com.andharness.ru.domain.notify.NotificationEvent
import com.andharness.ru.domain.person.InteractionDirection
import com.andharness.ru.domain.person.MessagingIndexPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Notification listener that keeps an in-memory snapshot of active notifications so the
 * `notification_*` tools can list, open, dismiss and reply to them. The user must enable it manually
 * in Settings > Notifications > Special access > Notification access.
 *
 * It also feeds the `notification` automation trigger: each posted notification's package, title and
 * text are forwarded (non-blocking) to [AutomationEventBus].
 *
 * Personal base (update.md §4.2/§4.4): additionally, and **only when the user opted in** and the
 * package is in the configured allow-list, the notification is filed as a MESSENGER/EMAIL/SMS
 * interaction on the conversation/sender identity (**bounded gist** in `interactions`) **and** its
 * messages are written in **full** to the encrypted, device-only `stored_messages` store for later
 * search/summaries. A `MessagingStyle` is expanded to one row per message (text + timestamp + sender),
 * a plain notification to a single row from its title/text; each row carries a hashed `externalRef` so
 * a re-posted notification does not duplicate messages. Notifications from non-listed packages are
 * ignored entirely.
 *
 * Reference: https://developer.android.com/reference/android/service/notification/NotificationListenerService
 */
class NotificationRelayService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refresh()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        synchronized(lock) { active[sbn.key] = sbn }
        val extras = sbn.notification?.extras
        val packageName = sbn.packageName.orEmpty()
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        AutomationEventBus.notificationPosted(
            packageName = packageName,
            title = title,
            text = text
        )
        indexNotification(
            key = sbn.key,
            packageName = packageName,
            title = title,
            text = text,
            at = sbn.postTime,
            notification = sbn.notification,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        synchronized(lock) { active.remove(sbn.key) }
    }

    /**
     * Parsed messaging content of one notification: the raw per-message triples read from the
     * `MessagingStyle`/`EXTRA_TEXT_LINES`, the group conversation title (if any) and whether the
     * messages carried real timestamps (needed for stable dedup keys).
     */
    private data class ParsedMessages(
        val messages: List<NotificationMessageExtraction.RawMessage>,
        val conversationTitle: String?,
        val hasTimestamps: Boolean
    )

    /**
     * Best-effort, non-blocking ingestion of one notification into the personal base.
     *
     * The opt-in + package-list gate lives in [MessagingIndexPolicy]; when it rejects the
     * notification nothing is read into the base. A `MessagingStyle` (including a notification that
     * only carries `EXTRA_MESSAGES`) is expanded to one `stored_messages` row per message; otherwise
     * `EXTRA_TEXT_LINES` or the single `EXTRA_TITLE`/`EXTRA_TEXT` pair is used. The interaction table
     * still receives only a bounded gist ([text]).
     */
    private fun indexNotification(
        key: String,
        packageName: String,
        title: String,
        text: String,
        at: Long,
        notification: Notification?
    ) {
        if (!NotificationRoutingPolicy.hasIndexableTitle(title)) return
        indexingScope.launch {
            try {
                val entry = personIngestEntryPoint(applicationContext)
                val config = entry.messagingIndexConfigStore()
                if (!MessagingIndexPolicy.isEligible(config.isEnabled(), packageName, config.packages())) {
                    return@launch
                }
                val parsed = parseMessages(notification, at)
                val extracted = NotificationMessageExtraction.extract(
                    raw = parsed.messages,
                    conversationTitle = parsed.conversationTitle,
                    fallbackSender = title,
                    fallbackText = text,
                    fallbackAt = at,
                )
                if (extracted.messages.isEmpty()) return@launch

                val identity = NotificationRoutingPolicy.identityFor(
                    conversationKey = extracted.conversationKey,
                    title = title,
                    packageName = packageName,
                )
                val personId = entry.personIndexer().upsertIdentity(
                    type = identity.type,
                    value = identity.value,
                    appPackage = identity.appPackage,
                    label = identity.value,
                )

                storeMessages(
                    repository = entry.storedMessagesRepository(),
                    packageName = packageName,
                    key = key,
                    extracted = extracted,
                    hasTimestamps = parsed.hasTimestamps,
                    personId = personId,
                )

                // The interaction/gist stays bounded (≤80 chars) and de-duped by the notification key.
                val last = extracted.messages.last()
                entry.personIndexer().recordInteraction(
                    channel = MessagingIndexPolicy.channelFor(packageName),
                    direction = InteractionDirection.INCOMING,
                    at = last.at,
                    personIdOrIdentity = PersonRef.Identity(
                        type = identity.type,
                        value = identity.value,
                        appPackage = identity.appPackage,
                        label = identity.value,
                    ),
                    title = identity.value,
                    summary = last.text,
                    externalRef = key,
                    sourceApp = packageName,
                )
                // Idea #17: triage the same, already-eligible event. The engine applies its own
                // opt-in/private-mode/memorize gates.
                try {
                    notificationTriageEntryPoint(applicationContext).notificationTriageEngine().onEvent(
                        NotificationEvent(
                            app = packageName,
                            sender = identity.value,
                            title = identity.value,
                            text = last.text,
                            at = last.at,
                            isGroupOrBulk = extracted.isGroup,
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "notification triage failed", e)
                }
            } catch (e: Exception) {
                Log.w(TAG, "notification indexing failed", e)
            }
        }
    }

    /**
     * Reads the per-message content of [notification] into plain triples.
     *
     * The messages of a `MessagingStyle` (also when the app only sets the legacy
     * `EXTRA_MESSAGES` bundles) are decoded with the public
     * [Notification.MessagingStyle.Message.getMessagesFromBundleArray]; the group title comes from
     * `EXTRA_CONVERSATION_TITLE`. When there is no styled message, `EXTRA_TEXT_LINES` (one line per
     * message, no timestamp) is used; only then does the caller fall back to the single
     * `EXTRA_TITLE`/`EXTRA_TEXT` pair.
     */
    private fun parseMessages(notification: Notification?, fallbackAt: Long): ParsedMessages {
        val extras = notification?.extras
        val styled = readStyledMessages(extras)
        if (styled.isNotEmpty()) {
            val conversationTitle = extras
                ?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
            return ParsedMessages(styled, conversationTitle, hasTimestamps = true)
        }
        val lines = readTextLines(extras, fallbackAt)
        if (lines.isNotEmpty()) {
            return ParsedMessages(lines, conversationTitle = null, hasTimestamps = false)
        }
        return ParsedMessages(emptyList(), conversationTitle = null, hasTimestamps = false)
    }

    /** Decodes the `EXTRA_MESSAGES` bundles (API 30+ parser) into plain triples; blank texts dropped. */
    private fun readStyledMessages(extras: android.os.Bundle?): List<NotificationMessageExtraction.RawMessage> {
        if (extras == null) return emptyList()
        return try {
            @Suppress("DEPRECATION")
            val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles).mapNotNull { message ->
                val body = message.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                NotificationMessageExtraction.RawMessage(
                    text = body,
                    at = message.timestamp,
                    sender = senderName(message),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "reading MessagingStyle messages failed", e)
            emptyList()
        }
    }

    /** Decodes the `EXTRA_TEXT_LINES` summary lines (no per-line time) into plain triples. */
    private fun readTextLines(
        extras: android.os.Bundle?,
        fallbackAt: Long
    ): List<NotificationMessageExtraction.RawMessage> {
        if (extras == null) return emptyList()
        return try {
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) ?: return emptyList()
            lines.mapNotNull { line ->
                val body = line?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NotificationMessageExtraction.RawMessage(text = body, at = fallbackAt, sender = null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "reading notification text lines failed", e)
            emptyList()
        }
    }

    /** Sender display name of a `MessagingStyle.Message` (`Person.name` on API 28+, else the legacy sender). */
    private fun senderName(message: Notification.MessagingStyle.Message): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            message.senderPerson?.name?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        @Suppress("DEPRECATION")
        return message.sender?.toString()?.takeIf { it.isNotBlank() }
    }

    /**
     * Writes each extracted message into `stored_messages` (full text), de-duplicated by a hashed
     * `externalRef`. `hasTimestamps = false` (the `EXTRA_TEXT_LINES` fallback, which has no per-line
     * time) uses a time-independent key so a re-post with a new [at][StatusBarNotification.postTime]
     * does not duplicate the line.
     */
    private suspend fun storeMessages(
        repository: StoredMessagesRepository,
        packageName: String,
        key: String,
        extracted: NotificationMessageExtraction.Result,
        hasTimestamps: Boolean,
        personId: Long?
    ) {
        val sourceType = NotificationMessageExtraction.sourceTypeFor(packageName)
        val createdAt = System.currentTimeMillis()
        extracted.messages.forEachIndexed { index, message ->
            val externalRef = MessageExternalRefs.notification(
                packageName = packageName,
                key = key,
                index = index,
                text = message.text,
                at = NotificationRoutingPolicy.dedupAt(hasTimestamps, message.at),
            )
            if (repository.findByExternalRef(externalRef) != null) return@forEachIndexed
            repository.insert(
                StoredMessage(
                    id = 0,
                    sourceType = sourceType,
                    packageName = packageName,
                    conversationKey = extracted.conversationKey,
                    personId = personId,
                    direction = StoredMessageDirection.INCOMING,
                    sender = NotificationRoutingPolicy.senderFor(message.sender, extracted.conversationKey),
                    isGroup = extracted.isGroup,
                    at = message.at,
                    text = message.text,
                    externalRef = externalRef,
                    createdAt = createdAt,
                )
            )
        }
    }

    private fun refresh() {
        val current = try {
            activeNotifications ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        synchronized(lock) {
            active.clear()
            current.forEach { active[it.key] = it }
        }
    }

    companion object {
        private const val TAG = "Andharness"

        private val lock = Any()
        private val active = LinkedHashMap<String, StatusBarNotification>()

        /** Off-main-thread scope for the best-effort personal-base ingestion. */
        private val indexingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        var instance: NotificationRelayService? = null
            private set

        fun isEnabled(): Boolean = instance != null

        fun snapshot(): List<StatusBarNotification> = synchronized(lock) { active.values.toList() }

        fun find(key: String): StatusBarNotification? = synchronized(lock) { active[key] }
    }
}
