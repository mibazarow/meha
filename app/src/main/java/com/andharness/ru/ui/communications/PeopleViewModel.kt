package com.andharness.ru.ui.communications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.domain.person.Person
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the «Люди» screen: a bounded newest-first list of [Person]s with a debounced name search.
 *
 * It reads the existing personal base directly (no tool/handler round-trip): the browse list comes
 * from [PersonRepository.recent], search from [PersonRepository.searchByName] (the same
 * case-insensitive substring search the person tools use), and each row's short identity hint from
 * [PersonIdentityRepository.forPerson].
 *
 * Both reads are bounded ([LIST_LIMIT] / [SEARCH_LIMIT]) and so is the per-person identity read, so
 * the screen cannot walk an unbounded database.
 */
@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val personRepository: PersonRepository,
    private val identityRepository: PersonIdentityRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PeopleUiState())
    val uiState: StateFlow<PeopleUiState> = _uiState.asStateFlow()

    /** In-flight debounced search; cancelled whenever the query changes. */
    private var searchJob: Job? = null

    init {
        refresh()
    }

    /** (Re)loads the newest-first people list and its identity hints; clears any stale message. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true, message = null) }
        viewModelScope.launch {
            runCatching { toItems(personRepository.recent(LIST_LIMIT)) }
                .onSuccess { items ->
                    _uiState.update { it.copy(people = items, isLoading = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = failureText("Не удалось прочитать людей", error),
                        )
                    }
                }
        }
    }

    /**
     * Updates the query and, after a short debounce, runs the name search. A blank query clears the
     * hits and shows the full list again without touching the database.
     */
    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query, message = null) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            // Сбрасываем прошлые hits, чтобы новый запрос не показывал устаревшие строки.
            _uiState.update { it.copy(results = emptyList(), isSearching = true) }
            delay(SEARCH_DEBOUNCE_MS)
            runCatching { toItems(personRepository.searchByName(query, SEARCH_LIMIT)) }
                .onSuccess { hits ->
                    _uiState.update { it.copy(results = hits, isSearching = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            message = failureText("Не удалось выполнить поиск", error),
                        )
                    }
                }
        }
    }

    /** Resolves each person's short identity hint from the unified base, keeping the read bounded. */
    private suspend fun toItems(persons: List<Person>): List<PeopleItem> = persons.map { person ->
        PeopleItem(
            person = person,
            hint = identityHint(identityRepository.forPerson(person.id)),
        )
    }

    /** One consistent «не удалось …: причина» line for every failure branch above. */
    private fun failureText(prefix: String, error: Throwable): String =
        "$prefix: ${error.message ?: error::class.simpleName}"

    private companion object {
        /** People shown when browsing; bounds the list and its per-row identity reads. */
        const val LIST_LIMIT = 120

        /** People resolved per name search, bounding the follow-up identity reads. */
        const val SEARCH_LIMIT = 40

        /** Quiet window before a keystroke turns into a Room scan. */
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
