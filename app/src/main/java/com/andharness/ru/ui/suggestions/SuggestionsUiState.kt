package com.andharness.ru.ui.suggestions

import com.andharness.ru.domain.usage.AutomationSuggestion

data class SuggestionsUiState(
    val suggestions: List<AutomationSuggestion> = emptyList(),
    val isLoading: Boolean = true,
    /** False when the special usage-access grant is missing — the screen says so honestly. */
    val usageAccessGranted: Boolean = true,
    /** Id of the suggestion whose rule is currently being created, for button feedback. */
    val creatingId: String? = null,
    val error: String? = null
)
