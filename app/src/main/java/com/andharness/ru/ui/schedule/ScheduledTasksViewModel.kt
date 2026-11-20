package com.andharness.ru.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.data.schedule.ScheduledTaskActions
import com.andharness.ru.domain.schedule.ScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the «Задачи по расписанию» screen. Delete/toggle go through [ScheduledTaskActions], the
 * same shared logic the chat tools (`schedule_delete` / `schedule_toggle`) use, so cancelling here
 * cancels the identical WorkManager work item.
 */
@HiltViewModel
class ScheduledTasksViewModel @Inject constructor(
    repository: ScheduleRepository,
    private val actions: ScheduledTaskActions
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduledTasksUiState())
    val uiState: StateFlow<ScheduledTasksUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { tasks ->
                _uiState.update { it.copy(tasks = tasks, isLoading = false) }
            }
        }
    }

    fun delete(taskId: Long) {
        viewModelScope.launch { actions.delete(taskId) }
    }

    fun setEnabled(taskId: Long, enabled: Boolean) {
        viewModelScope.launch { actions.setEnabled(taskId, enabled) }
    }
}
