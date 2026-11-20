package com.andharness.ru.data.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Honest, process-wide status of the background "core" ([com.andharness.ru.bg.WatchdogService]).
 *
 * It exists so that diagnostics can say the truth: whether the core is actually running and, when it
 * is not, WHY (update.md §1.4 — «честный отчёт "не работает"»). Never report "running" just because
 * a feature is enabled.
 *
 * A plain singleton [StateFlow] is enough: it survives configuration changes, is observable from
 * Compose/ViewModel, and does not need to outlive the process (persisted restart bookkeeping lives
 * in Room, not here).
 */
data class WatchdogStatus(
    val running: Boolean = false,
    val reason: String? = WatchdogState.REASON_NOT_STARTED,
    val startedAt: Long = 0L
)
