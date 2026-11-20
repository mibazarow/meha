package com.andharness.ru.domain.assist


/**
 * Cheap aggregate counts for `self_diagnostics`: notes vs long-term memory, plus the memory
 * histogram by importance (0..2). All values come from `COUNT(*)`/`GROUP BY` queries, never a scan.
 */
data class AssistStats(
    val noteCount: Int,
    val memoryCount: Int,
    val memoryByImportance: Map<Int, Int>
)
