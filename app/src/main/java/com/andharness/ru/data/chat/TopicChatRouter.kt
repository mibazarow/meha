package com.andharness.ru.data.chat

import android.util.Log
import com.andharness.ru.domain.chat.ChatRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** The chat chosen for a topic, and whether [created] it had to be created. */
data class TopicChatResolution(
    val chatId: Long,
    val created: Boolean,
)

/**
 * Pure, Android-free find-or-create core: reuse [storedChatId] when that chat still exists, otherwise
 * create a chat titled [title]. Writes NO message — callers whose own turn persists the user message
 * (the overlay hand-off) use this so the question is not duplicated.
 *
 * Extracted so the reuse-vs-recreate decision is testable on the JVM with a fake [ChatRepository],
 * without a `Context` or `SharedPreferences`.
 */
suspend fun ensureTopicChat(
    title: String,
    storedChatId: Long?,
    chatRepository: ChatRepository,
): TopicChatResolution {
    val existing = storedChatId?.takeIf { chatRepository.chatExists(it) }
    val chatId = existing ?: chatRepository.createChat(title)
    return TopicChatResolution(chatId, created = existing == null)
}

/**
 * [ensureTopicChat] plus [text] appended under [role] — the pure core of [TopicChatRouter.route].
 * Kept separate from [ensureTopicChat] because some producers (the overlay) append the user's own
 * message themselves and must only reuse the chat.
 */
suspend fun resolveTopicChat(
    title: String,
    text: String,
    storedChatId: Long?,
    chatRepository: ChatRepository,
    role: String = ROLE_ASSISTANT,
): TopicChatResolution {
    val resolution = ensureTopicChat(title, storedChatId, chatRepository)
    chatRepository.addMessage(resolution.chatId, role, text)
    return resolution
}

/**
 * Generalises the notification topic→chat routing to any producer: the chat is remembered per
 * [topic] in [TopicChatStore], the first event creates it, later ones reuse it, and a remembered
 * chat that was deleted is recreated. Notification behaviour is unchanged ([NotificationChatRouter]
 * delegates here); the floating assistant uses [ensure] so every overlay request lands in one
 * persistent chat.
 *
 * Never throws: a failure (e.g. the database is unavailable) is logged honestly and reported as
 * "no chat", so a caller can fall back instead of crashing a receiver or the overlay Service.
 */
@Singleton
class TopicChatRouter @Inject constructor(
    private val chatRepository: ChatRepository,
    private val store: TopicChatStore,
) {

    /**
     * Finds [topic]'s chat, creating it when nothing was stored or the stored chat was deleted, and
     * returns its id; [NO_CHAT_ID] when no chat could be ensured. Appends no message.
     */
    suspend fun ensure(topic: String, title: String): Long = try {
        val resolution = ensureTopicChat(title, store.chatId(topic), chatRepository)
        if (resolution.created) store.setChatId(topic, resolution.chatId)
        resolution.chatId
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Ensuring a chat for topic «$topic» failed: ${e.message}", e)
        NO_CHAT_ID
    }

    /** Routes [topic] into a chat, appends [text] under [role], and returns its id. */
    suspend fun route(
        topic: String,
        title: String,
        text: String,
        role: String = ROLE_ASSISTANT,
    ): Long = routeIfNeeded(topic, title, text, chatId = null, role = role) ?: NO_CHAT_ID

    /**
     * Routes only when the event carries no chat: a non-null [chatId] means the caller already
     * targets a chat, so null is returned and the caller keeps its own id. Otherwise the chat is
     * reused/created (see [resolveTopicChat]) and its id is returned; null when routing failed.
     */
    suspend fun routeIfNeeded(
        topic: String,
        title: String,
        text: String,
        chatId: Long?,
        role: String = ROLE_ASSISTANT,
    ): Long? {
        if (chatId != null) return null
        return try {
            val resolution = resolveTopicChat(title, text, store.chatId(topic), chatRepository, role)
            if (resolution.created) store.setChatId(topic, resolution.chatId)
            resolution.chatId
        } catch (e: CancellationException) {
            // Never swallow structured-concurrency cancellation (e.g. the crash path's timeout).
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Routing topic «$topic» into a chat failed: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "TopicChatRouter"

        /** Returned by [route]/[ensure] when no chat could be created; also the "no chat" sentinel. */
        const val NO_CHAT_ID = -1L
    }
}

/** Role routed content is written under when the caller does not specify one. */
private const val ROLE_ASSISTANT = "assistant"
