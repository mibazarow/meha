package com.andharness.ru.domain.notify

/**
 * Pure decision for update.md bug #5: a CHAT-RELATED notification (task/run finished, reminder,
 * scheduled result) must be suppressed while the user is actually looking at that same chat.
 *
 * The rule is intentionally split from Android state so it can be unit-tested exhaustively:
 *
 * ```
 * suppress  ⟺  app is in the foreground  AND  the notification targets a chat
 *               AND  that chat is the one currently open
 * ```
 *
 * Consequences (all covered by [com.andharness.ru.domain.notify.ChatNotificationGateTest]):
 * - a non-chat notification ([targetChatId] == null) is never suppressed (FGS / core stay intact);
 * - while the app is backgrounded or closed, nothing is suppressed;
 * - while another screen/chat is open ([activeChatId] != [targetChatId]), the notification is shown.
 *
 * State is published by [com.andharness.ru.domain.ui.ActiveChatTracker]; the gate itself has no
 * Android dependency.
 */
object ChatNotificationGate {

    fun shouldSuppress(
        appForeground: Boolean,
        activeChatId: Long?,
        targetChatId: Long?,
    ): Boolean {
        if (!appForeground) return false
        if (targetChatId == null) return false
        return activeChatId == targetChatId
    }
}
