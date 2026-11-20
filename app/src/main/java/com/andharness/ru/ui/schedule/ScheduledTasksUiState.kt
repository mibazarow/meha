package com.andharness.ru.ui.schedule

import com.andharness.ru.domain.schedule.ScheduledTask

data class ScheduledTasksUiState(
    val tasks: List<ScheduledTask> = emptyList(),
    val isLoading: Boolean = true
)
