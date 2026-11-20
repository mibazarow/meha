package com.andharness.ru.data.db.dao


/** One row of the per-importance histogram of long-term memories, for `self_diagnostics`. */
data class ImportanceCount(
    val importance: Int,
    val count: Int
)
