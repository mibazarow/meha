package com.andharness.ru.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.di.DefaultDispatcher
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.chat.ChatSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val chatSearch: ChatSearch,
    @DefaultDispatcher private val backgroundDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    /**
     * The in-flight debounced search. Replaced (the previous one cancelled) on every keystroke, so
     * only the most recent query ever reaches [ChatSearch]; a blank query cancels it outright.
     */
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            // Combine the chat rows with their last message. `observeLastMessages()` returns exactly
            // one row per chat (single SQL query), so the join below is O(chats) with no per-row DB
            // access — the list never does N+1. The mapping runs off the main thread.
            combine(
                repository.observeChats(),
                repository.observeLastMessages()
            ) { chats, lastMessages ->
                val byChat = lastMessages.associateBy { it.chatId }
                chats.map { chat -> ChatListRow(chat, byChat[chat.id]) }
            }
                .flowOn(backgroundDispatcher)
                // Merge into the current state instead of overwriting it: the search fields (query,
                // results, isSearching) are owned by `onQueryChange` and must survive list updates.
                .collect { rows ->
                    _uiState.update { it.copy(chats = rows, isLoading = false) }
                }
        }
    }

    /**
     * Updates the search query and (re)starts the debounced search. Blank input shows the normal
     * chat list again and cancels any pending search; a non-blank query is debounced by
     * [SEARCH_DEBOUNCE_MS] and the previous job is cancelled first, so a burst of keystrokes issues
     * exactly one [ChatSearch.searchChats] call — for the final query only.
     */
    fun onQueryChange(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchJob = null
            _uiState.update { it.copy(query = query, results = emptyList(), isSearching = false) }
            return
        }
        _uiState.update { it.copy(query = query, isSearching = true) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val results = chatSearch.searchChats(query, SEARCH_RESULT_LIMIT)
            _uiState.update { it.copy(results = results, isSearching = false) }
        }
    }

    fun createNewChat(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.createChat("Новый чат")
            repository.pruneToLimit(MAX_CHATS)
            onCreated(id)
        }
    }

    fun delete(chatId: Long) {
        viewModelScope.launch {
            repository.deleteChat(chatId)
        }
    }

    /**
     * Flips the pinned state of [chatId]: pinned chats float to the top of the list (the DAO orders
     * them first), unpinned ones fall back into recency order. The new list is re-emitted by the
     * repository flow, so no local state edit is needed.
     */
    fun togglePin(chatId: Long) {
        val chat = _uiState.value.chats.firstOrNull { it.chat.id == chatId }?.chat ?: return
        viewModelScope.launch {
            repository.setPinned(chatId, chat.pinnedAt <= 0L)
        }
    }

    companion object {
        /**
         * Debounce window for the chat-list search. Long enough to coalesce a burst of keystrokes,
         * short enough to feel immediate (Telegram-like). Cancellation of the prior job is what
         * guarantees only the final query is searched.
         */
        internal const val SEARCH_DEBOUNCE_MS = 275L

        /** Max search results requested from [ChatSearch] (bounded UI list). */
        internal const val SEARCH_RESULT_LIMIT = 20

        /**
         * Chat history is always pruned to at most this many chats. It used to be a user setting
         * (`SettingsEntity.maxChats`), which is now dormant — the column stays for compatibility and
         * no Room migration is performed.
         */
        private const val MAX_CHATS = 400
    }
}
