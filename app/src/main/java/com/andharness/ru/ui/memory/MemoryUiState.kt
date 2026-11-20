package com.andharness.ru.ui.memory

import com.andharness.ru.data.db.entity.NoteEntity

/**
 * State of the «Что агент помнит» screen.
 *
 * [memories] is the bounded newest-first list shown when there is no query. [results] holds the
 * repository's lexical memory search for a non-blank [query]; [visibleMemories] picks whichever the
 * screen must render. Search is debounced inside [MemoryViewModel] so a fast typist does not fire
 * one Room scan per keystroke.
 *
 * [message] carries an honest failure/status line only — it is set to null on every successful
 * action, so a stale error never lingers.
 */
data class MemoryUiState(
    val memories: List<NoteEntity> = emptyList(),
    val query: String = "",
    val results: List<NoteEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isSearching: Boolean = false,
    val message: String? = null
) {
    /** True while the user is filtering the list by [query] rather than browsing everything. */
    val isSearchActive: Boolean get() = query.isNotBlank()

    /** What the list area must show: search hits while a query is active, otherwise all memories. */
    val visibleMemories: List<NoteEntity> get() = if (isSearchActive) results else memories
}
