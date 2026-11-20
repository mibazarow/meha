package com.andharness.ru.ui.db

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.data.db.viewer.DatabaseViewerRepository
import com.andharness.ru.data.db.viewer.DatabaseViewerSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the read-only database viewer. It exposes only selections: the section overview (with per
 * table counts) and one paginated page at a time. There are **no** mutation entry points — writes
 * can only happen through the agent in chat.
 */
@HiltViewModel
class DatabaseViewerViewModel @Inject constructor(
    private val repository: DatabaseViewerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DatabaseViewerUiState())
    val uiState: StateFlow<DatabaseViewerUiState> = _uiState.asStateFlow()

    init {
        loadOverview()
    }

    private fun loadOverview() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.counts() }
                .onSuccess { counts ->
                    _uiState.update { state ->
                        state.copy(
                            sections = DatabaseViewerSpec.sections,
                            counts = counts.associate { it.table to it.count },
                            isLoading = false
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Не удалось прочитать базу: ${error.message ?: error::class.simpleName}"
                        )
                    }
                }
        }
    }

    fun openTable(table: String) {
        _uiState.update {
            it.copy(
                selectedTable = table,
                selectedLabel = DatabaseViewerSpec.label(table),
                isLoading = true,
                error = null,
                page = 0
            )
        }
        loadPage(table, 0)
    }

    /** Leaves the table view and returns to the section overview. */
    fun closeTable() {
        _uiState.update {
            it.copy(
                selectedTable = null,
                selectedLabel = "",
                columns = emptyList(),
                rows = emptyList(),
                page = 0,
                pageCount = 0,
                totalCount = 0,
                error = null
            )
        }
    }

    fun nextPage() {
        val state = _uiState.value
        val table = state.selectedTable ?: return
        if (state.page + 1 >= state.pageCount) return
        loadPage(table, state.page + 1)
    }

    fun previousPage() {
        val state = _uiState.value
        val table = state.selectedTable ?: return
        if (state.page <= 0) return
        loadPage(table, state.page - 1)
    }

    private fun loadPage(table: String, page: Int) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.page(table, page) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            columns = result.columns,
                            rows = result.rows,
                            page = result.page,
                            pageCount = result.pageCount,
                            totalCount = result.totalCount,
                            isLoading = false
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Не удалось прочитать таблицу: ${error.message ?: error::class.simpleName}"
                        )
                    }
                }
        }
    }
}
