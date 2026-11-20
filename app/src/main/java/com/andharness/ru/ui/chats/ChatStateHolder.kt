package com.andharness.ru.ui.chats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the observable state of one chat screen. It is shared by [ChatViewModel] and the
 * responsibility-specific collaborators the ViewModel delegates to, so each collaborator can read
 * and update the same state without owning it.
 */
internal class ChatStateHolder {

    private val _state = MutableStateFlow(ChatUiState())

    /** Immutable snapshot stream the screen observes. */
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Current snapshot, for synchronous reads inside collaborators. */
    val current: ChatUiState get() = _state.value

    fun update(transform: (ChatUiState) -> ChatUiState) {
        _state.update(transform)
    }
}
