package com.andharness.ru.data.db.viewer


data class DbPage(
    val table: String,
    val label: String,
    val columns: List<String>,
    val rows: List<DbRow>,
    val totalCount: Int,
    val page: Int,
    val pageCount: Int
)
