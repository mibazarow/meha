package com.andharness.ru.data.notifications

import com.andharness.ru.data.chat.TopicChatResolution
import com.andharness.ru.data.chat.TopicChatRouter
import com.andharness.ru.data.chat.resolveTopicChat
import com.andharness.ru.domain.chat.ChatRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable topic keys and human titles for notifications that must land in a chat. A topic is the
 * identity of one notification *stream*: the same topic always reuses the same chat, so repeated
 * posts append to one place rather than spawning a chat per notification.
 */
object NotificationChatTopics {
    const val GENERAL = "general"
    const val GENERAL_TITLE = "Уведомления"

    const val SCHEDULE = "schedule"
    const val SCHEDULE_TITLE = "Напоминания"

    const val ALARM = "alarm"
    const val ALARM_TITLE = "Будильники"

    const val AUTOMATION = "automation"
    const val AUTOMATION_TITLE = "Автоматизации"

    const val CRASH = "crash"
    const val CRASH_TITLE = "Сбои"

    /**
     * Derives the topic of a chat-less, id-keyed notification from its stable [baseId], so the same
     * notification kind reuses one chat. Pure and deterministic, therefore unit-testable on the JVM.
     */
    fun forBaseId(baseId: Int): String = "notification_$baseId"
}

/**
 * Notification-specific facade over the neutral [TopicChatRouter]: notification producers keep their
 * own names, while the reuse/recreate/append rules and the topic→chat persistence live in
 * `data/chat` so the floating assistant can share them.
 */
suspend fun resolveNotificationChat(
    title: String,
    text: String,
    storedChatId: Long?,
    chatRepository: ChatRepository,
): TopicChatResolution = resolveTopicChat(
    title = title,
    text = text,
    storedChatId = storedChatId,
    chatRepository = chatRepository,
)

/**
 * Routes a notification into a chat so tapping it opens a CHAT (with the notification's content
 * already written there) instead of the home screen.
 *
 * Delegates entirely to [TopicChatRouter]; the public surface is unchanged so notification
 * producers are untouched. The chat is remembered per topic, the first post creates it, later posts
 * append, and a remembered chat that was deleted is recreated. [routeIfNeeded] is the variant for
 * producers whose notification may already carry a chat: an explicit non-null id is left untouched.
 *
 * Never throws: failures are logged and reported as "no chat" by the delegate.
 */
@Singleton
class NotificationChatRouter @Inject constructor(
    private val delegate: TopicChatRouter,
) {

    /** Routes [topic] into a chat and returns its id, or [NO_CHAT_ID] when no chat could be made. */
    suspend fun route(topic: String, title: String, text: String): Long =
        delegate.route(topic, title, text)

    /**
     * Routes only when the notification carries no chat: a non-null [chatId] means the caller
     * already targets a chat, so null is returned and the caller keeps its own id.
     */
    suspend fun routeIfNeeded(topic: String, title: String, text: String, chatId: Long?): Long? =
        delegate.routeIfNeeded(topic, title, text, chatId)

    companion object {
        /** Returned by [route] when no chat could be created; also the "no chat" sentinel. */
        const val NO_CHAT_ID = TopicChatRouter.NO_CHAT_ID
    }
}
