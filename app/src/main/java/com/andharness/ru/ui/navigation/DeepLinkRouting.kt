package com.andharness.ru.ui.navigation

/**
 * Pure (Android-free) mapping for the notification / inbound deep-link consumption path (BUG-051).
 *
 * Extracted from the inline logic in `MainActivity` and `AppNavHost` so the intent→target mapping
 * and the idempotency key are a single, unit-testable source of truth with no Android `Context`:
 *
 * - [route] turns a chat id into a navigation destination: a specific `chat/{id}` route when the
 *   notification names a chat, or the app's normal start screen ([AppDestination.HOME]) when it
 *   carries no chat (e.g. a chat-less notification). This is exactly the "opens the right place, not just
 *   the app" rule from BUG-051.
 * - [key] is the stable identity of a deep-link delivery (`chatId:messageId`, with `-1L` standing
 *   for "no message"). Identical inputs produce an identical key, which is what lets
 *   `MainActivity` / `AppNavHost` treat a re-delivery of the same tap (rotation, process-death
 *   restore, a repeated content intent) as a no-op instead of navigating a second time.
 */
object DeepLinkRouting {

    fun route(chatId: Long?): String =
        if (chatId != null) AppDestination.chat(chatId) else AppDestination.HOME

    fun key(chatId: Long?, messageId: Long?): String =
        "$chatId:${messageId ?: -1L}"
}
