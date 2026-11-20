package com.andharness.ru.ui.communications

import com.andharness.ru.domain.person.Person

/**
 * One row of the «Люди» list: the [person] plus a short [hint] (its first identity handles), which
 * is `null` when the person has no indexed phone/email/messenger yet.
 */
data class PeopleItem(
    val person: Person,
    val hint: String? = null,
)

/**
 * State of the «Люди» screen.
 *
 * [people] is the bounded newest-first list shown when there is no query; [results] holds the
 * name-search hits for a non-blank [query]; [visiblePeople] picks whichever the screen must render.
 * Search is debounced inside [PeopleViewModel] so a fast typist does not fire one Room scan per
 * keystroke.
 *
 * [message] carries an honest failure line only — it is cleared on every successful action.
 */
data class PeopleUiState(
    val people: List<PeopleItem> = emptyList(),
    val query: String = "",
    val results: List<PeopleItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSearching: Boolean = false,
    val message: String? = null,
) {
    /** True while the user is filtering by [query] rather than browsing everyone. */
    val isSearchActive: Boolean get() = query.isNotBlank()

    /** What the list area must show: search hits while a query is active, otherwise all people. */
    val visiblePeople: List<PeopleItem> get() = if (isSearchActive) results else people
}
