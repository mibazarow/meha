package com.andharness.ru.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.domain.settings.LlmSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    settingsStore: LlmSettingsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.config.collect { config ->
                val status = when {
                    config.endpoint.isBlank() -> "Подключение не настроено"
                    config.model.isBlank() -> "Эндпоинт: ${config.endpoint}"
                    else -> "Текущая модель: ${config.model}"
                }
                val configured = config.endpoint.isNotBlank()
                _uiState.update { it.copy(status = status, configured = configured) }
            }
        }
    }
}
