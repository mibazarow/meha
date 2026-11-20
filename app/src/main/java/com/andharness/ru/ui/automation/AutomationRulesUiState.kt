package com.andharness.ru.ui.automation

import com.andharness.ru.domain.automation.AutomationRule

data class AutomationRulesUiState(
    val rules: List<AutomationRule> = emptyList(),
    val isLoading: Boolean = true,
    /** Honest failure text for the last mutation, or null when the last action succeeded. */
    val error: String? = null
)
