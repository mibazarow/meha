package com.andharness.ru.ui.db

import com.andharness.ru.data.db.viewer.DbRow
import com.andharness.ru.data.db.viewer.DbSection

data class DatabaseViewerUiState(
    val sections: List<DbSection> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val selectedTable: String? = null,
    val selectedLabel: String = "",
    val columns: List<String> = emptyList(),
    val rows: List<DbRow> = emptyList(),
    val page: Int = 0,
    val pageCount: Int = 0,
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null
)
