package com.andharness.ru.ui.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.data.automation.AutomationTriggerManager
import com.andharness.ru.domain.automation.AutomationRule
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the «Правила автоматизации» screen. It only persists and arms/cancels rules — creation
 * stays tool-driven (`automation_rule_create`), so the UI never builds an [AutomationRule].
 *
 * Toggle/delete mirror [com.andharness.ru.data.tools.automation.AutomationToolsHandler] exactly:
 * persist through [AutomationRuleRepository], then arm ([AutomationScheduler.schedule]) or cancel
 * ([AutomationScheduler.cancel]) the OS-scheduled work, and finally refresh
 * [AutomationTriggerManager] so `calendar_event` checks arm/disarm without an app restart.
 */
@HiltViewModel
class AutomationRulesViewModel @Inject constructor(
    private val repository: AutomationRuleRepository,
    private val scheduler: AutomationScheduler,
    private val triggerManager: AutomationTriggerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutomationRulesUiState())
    val uiState: StateFlow<AutomationRulesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { rules ->
                _uiState.update { it.copy(rules = rules, isLoading = false) }
            }
        }
    }

    /** Enables/disables a rule and keeps the scheduler in sync. */
    fun setEnabled(ruleId: Long, enabled: Boolean) {
        viewModelScope.launch {
            try {
                if (!repository.setEnabled(ruleId, enabled)) {
                    _uiState.update { it.copy(error = "Правило #$ruleId не найдено.") }
                    return@launch
                }
                val rule = repository.getById(ruleId)
                if (rule != null) {
                    if (enabled) scheduler.schedule(rule) else scheduler.cancel(ruleId)
                }
                triggerManager.refresh()
                _uiState.update { it.copy(error = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Не удалось изменить правило: ${e.message ?: e::class.simpleName}")
                }
            }
        }
    }

    /** Deletes a rule and cancels any scheduled work it owned. */
    fun delete(ruleId: Long) {
        viewModelScope.launch {
            try {
                if (!repository.delete(ruleId)) {
                    _uiState.update { it.copy(error = "Правило #$ruleId не найдено.") }
                    return@launch
                }
                scheduler.cancel(ruleId)
                triggerManager.refresh()
                _uiState.update { it.copy(error = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Не удалось удалить правило: ${e.message ?: e::class.simpleName}")
                }
            }
        }
    }
}
