package com.andharness.ru.data.db.viewer


data class DbTableCount(
    val table: String,
    val label: String,
    val isBig: Boolean,
    val count: Int
)
