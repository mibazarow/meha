package com.andharness.ru.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the single [SettingsUiState] instance shared by [SettingsViewModel] and its collaborators.
 * Collaborators mutate it through [update]; the screen observes [state]. [current] gives a
 * synchronous snapshot for decisions that must read the latest value without collecting.
 */
class SettingsStateHolder(initial: SettingsUiState) {

    private val _state = MutableStateFlow(initial)

    /** Observable state for the screen. */
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Latest snapshot, for synchronous reads inside collaborators. */
    val current: SettingsUiState get() = _state.value

    /** Applies [transform] to the current state atomically. */
    fun update(transform: (SettingsUiState) -> SettingsUiState) {
        _state.update(transform)
    }
}
