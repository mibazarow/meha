package com.andharness.ru.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.domain.assist.AssistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the «Что агент помнит» screen. It reads the agent's long-term memory (`kind = "memory"`)
 * through [AssistRepository] and offers three honest actions:
 *
 * - **search** reuses the repository's lexical recall ([AssistRepository.searchMemories]), so the
 *   screen and the agent rank/tokenise memories identically;
 * - **delete** goes through [AssistRepository.deleteMemory], which is scoped to `kind = "memory"`,
 *   so an ordinary note can never be removed from here;
 * - **forget all** deletes the ids currently loaded in a bounded loop (see [MEMORY_LIMIT]) and then
 *   re-reads the table, so the screen ends on the database's real state rather than a local guess.
 *
 * No DB schema, DAO or repository semantics are changed — this class only consumes the existing
 * [AssistRepository] API.
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repository: AssistRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    /** In-flight debounced search; cancelled whenever the query changes. */
    private var searchJob: Job? = null

    init {
        refresh()
    }

    /** (Re)loads the newest-first memory list. Called on entry and after a successful «забыть всё». */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.listMemories(MEMORY_LIMIT) }
                .onSuccess { memories ->
                    _uiState.update { it.copy(memories = memories, isLoading = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = failureText("Не удалось прочитать память", error)
                        )
                    }
                }
        }
    }

    /**
     * Updates the search query and, after a short debounce, runs the repository memory search. A
     * blank query clears the hits and shows the full list again without touching the database.
     */
    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query, message = null) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            // Сбрасываем прошлые hits, чтобы при новом запросе не показывать устаревшие строки.
            _uiState.update { it.copy(results = emptyList(), isSearching = true) }
            delay(SEARCH_DEBOUNCE_MS)
            runCatching { repository.searchMemories(query, MEMORY_LIMIT) }
                .onSuccess { hits ->
                    _uiState.update { it.copy(results = hits, isSearching = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            message = failureText("Не удалось выполнить поиск", error)
                        )
                    }
                }
        }
    }

    /**
     * Deletes one memory. [AssistRepository.deleteMemory] is scoped to `kind = "memory"`, so a
     * non-memory id is reported as not found instead of being removed. The row is dropped from the
     * local list/result immediately on success, which is what the DB now holds.
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteMemory(id) }
                .onSuccess { removed ->
                    if (removed) {
                        _uiState.update { state ->
                            state.copy(
                                memories = state.memories.filterNot { it.id == id },
                                results = state.results.filterNot { it.id == id },
                                message = null
                            )
                        }
                    } else {
                        _uiState.update { it.copy(message = "Заметка #$id не найдена.") }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(message = failureText("Не удалось удалить заметку", error))
                    }
                }
        }
    }

    /**
     * Forgets every memory the screen has loaded, then re-reads the table. Bounded by
     * [MEMORY_LIMIT] on purpose: the loop deletes exactly the ids it can see, and the follow-up
     * [AssistRepository.listMemories] shows whatever the database actually still contains, so the
     * screen never claims an empty memory that is not real.
     */
    fun forgetAll() {
        val ids = _uiState.value.memories.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var failures = 0
            ids.forEach { id ->
                runCatching { repository.deleteMemory(id) }.onFailure { failures++ }
            }
            val remaining = runCatching { repository.listMemories(MEMORY_LIMIT) }.getOrNull()
            _uiState.update { state ->
                state.copy(
                    memories = remaining ?: state.memories.filterNot { it.id in ids },
                    results = emptyList(),
                    query = "",
                    isLoading = false,
                    isSearching = false,
                    message = if (failures > 0) {
                        "Не удалось забыть заметок: $failures"
                    } else {
                        null
                    }
                )
            }
        }
    }

    /** One consistent «не удалось …: причина» line for every failure branch above. */
    private fun failureText(prefix: String, error: Throwable): String =
        "$prefix: ${error.message ?: error::class.simpleName}"

    private companion object {
        /**
         * Page of memories this screen holds. Matches the repository's own in-memory scan bound
         * (`AssistRepositoryImpl.MEMORY_SCAN_LIMIT = 500`), so search and «забыть всё» cover every
         * memory the agent can actually recall, while still keeping the read bounded.
         */
        const val MEMORY_LIMIT = 500

        /** Quiet window before a keystroke turns into a Room scan. */
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
