package com.andharness.ru.ui.suggestions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.data.usage.AutomationSuggestionEngine
import com.andharness.ru.domain.usage.AutomationSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the «Подсказки» screen (update.md idea #16). It works through [AutomationSuggestionEngine],
 * the same engine the `automation_suggestions` tool uses, so accepting here creates a rule exactly
 * like the chat path and dismissing here hides the suggestion from both surfaces.
 */
@HiltViewModel
class SuggestionsViewModel @Inject constructor(
    private val engine: AutomationSuggestionEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(SuggestionsUiState())
    val uiState: StateFlow<SuggestionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val granted = engine.hasUsageAccess()
            val suggestions = if (granted) engine.currentSuggestions() else emptyList()
            _uiState.update {
                it.copy(
                    suggestions = suggestions,
                    usageAccessGranted = granted,
                    isLoading = false
                )
            }
        }
    }

    fun accept(suggestion: AutomationSuggestion) {
        if (_uiState.value.creatingId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(creatingId = suggestion.id, error = null) }
            try {
                engine.accept(suggestion)
                _uiState.update { state ->
                    state.copy(
                        suggestions = state.suggestions.filterNot { it.id == suggestion.id },
                        creatingId = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(creatingId = null, error = "Не удалось создать правило: ${e.message}")
                }
            }
        }
    }

    fun dismiss(suggestion: AutomationSuggestion) {
        engine.dismiss(suggestion)
        _uiState.update { state ->
            state.copy(suggestions = state.suggestions.filterNot { it.id == suggestion.id })
        }
    }
}
