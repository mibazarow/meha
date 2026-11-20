package com.andharness.ru.ui.chats

import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.chat.ChatSearchResult

/**
 * One row of the chat list: the chat itself plus its most recent user/assistant message (null for a
 * chat that has no messages yet). Resolved by [ChatListViewModel] in a single pass so the row
 * composable only formats already-loaded data and never hits the database.
 */
data class ChatListRow(
    val chat: ChatEntity,
    val lastMessage: MessageEntity? = null
)

/**
 * State of the chat-list screen. The screen shows the normal chat list while [query] is blank; a
 * non-blank query switches it to the cross-chat search results ([results]) produced by
 * [com.andharness.ru.domain.chat.ChatSearch].
 *
 * @param query the raw text of the search field (blank = show the normal list).
 * @param results chat matches (title + message content, semantic + lexical) for the current query.
 * @param isSearching true while a debounced search is in flight, so the UI can show a progress
 *   indicator instead of a premature «Ничего не найдено».
 */
data class ChatListUiState(
    val chats: List<ChatListRow> = emptyList(),
    val isLoading: Boolean = true,
    val query: String = "",
    val results: List<ChatSearchResult> = emptyList(),
    val isSearching: Boolean = false
)
