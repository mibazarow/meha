package com.andharness.ru.domain.agent

/**
 * Pure decision that answers a single question: after a user-initiated agent turn finishes, should
 * Меха bring the ORIGINATING chat back to the front?
 *
 * This is the bug the policy exists for: while Меха automates another app (a UI-automation step or
 * an `app_launch`), the user is moved into that other app. When the turn finishes, nothing brings
 * the user back to the chat that asked, so the report is never seen.
 *
 * Return only when ALL of the following hold:
 *  - [userInitiated] — the turn came from a chat message or the floating assistant
 *    ([AgentRunPriority.INTERACTIVE]). Background/objective/scheduled/automation runs
 *    ([AgentRunPriority.BACKGROUND]) must never yank the user into the app.
 *  - [appInForeground] is false — if Меха's UI is already visible the user is looking at it, and a
 *    launch would be a pointless interruption.
 *  - [chatId] != null — there must be an originating chat to navigate back to.
 *
 * Kept Android-free on purpose so the whole truth table is unit-testable on the JVM. The Android
 * side that actually starts the Activity (and falls back to the chat-targeted result notification
 * when the platform refuses a background activity start) lives in
 * [com.andharness.ru.data.agent.ChatReturnLauncher].
 */
object BackgroundChatReturnPolicy {

    fun shouldReturnToOriginatingChat(
        userInitiated: Boolean,
        appInForeground: Boolean,
        chatId: Long?,
    ): Boolean = userInitiated && !appInForeground && chatId != null
}
