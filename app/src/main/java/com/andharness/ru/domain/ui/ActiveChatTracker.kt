package com.andharness.ru.domain.ui

/**
 * Process-wide "is the app visible, and which chat is the user looking at" state (update.md bug #5).
 *
 * It exists so a notification producer (a background worker, a foreground service, a tool handler)
 * can decide whether a chat-related notification would be redundant — see
 * [com.andharness.ru.domain.notify.ChatNotificationGate], which is the only decision that reads it.
 *
 * Writers:
 * - [com.andharness.ru.MainActivity] — [onAppForegrounded] / [onAppBackgrounded] (onStart/onStop);
 * - [com.andharness.ru.ui.chats.ChatViewModel] — [onChatOpened] / [onChatClosed] (per-chat nav entry).
 *
 * It is a plain object, not a Hilt singleton, because it is also read from non-injected call sites
 * (debug bridge) and from notification helpers constructed with a manual `Context`; keeping the state
 * process-wide avoids two divergent instances. All fields are `@Volatile` and are only ever touched
 * from the main thread (lifecycle callbacks), so no locking is needed.
 *
 * Backgrounding deliberately does NOT clear [activeChatId]: the activity is stopped but its
 * composition (and thus the open chat) survives, so after returning to the foreground the chat is
 * still the one in front of the user. The gate combines this with `isAppForeground`, so a
 * backgrounded app never suppresses anything.
 */
object ActiveChatTracker {

    @Volatile
    var isAppForeground: Boolean = false
        private set

    @Volatile
    var activeChatId: Long? = null
        private set

    fun onAppForegrounded() {
        isAppForeground = true
    }

    fun onAppBackgrounded() {
        isAppForeground = false
    }

    fun onChatOpened(chatId: Long) {
        activeChatId = chatId
    }

    fun onChatClosed(chatId: Long) {
        // Only clear when the chat being left is still the tracked one: ViewModels can be recreated
        // around the same destination, so a stale `onCleared` must not wipe a newer chat.
        if (activeChatId == chatId) activeChatId = null
    }

    /** Test/debug hook only — restores the process-default state. */
    fun reset() {
        isAppForeground = false
        activeChatId = null
    }
}
