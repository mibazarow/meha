package com.andharness.ru.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.data.undo.UndoStore
import com.andharness.ru.domain.audit.ToolAuditRepository
import com.andharness.ru.domain.undo.UndoExecutor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the «Что агент сделал» report.
 *
 * It joins the existing append-only audit log ([ToolAuditRepository]) with the M3 undo journal
 * ([UndoStore]) through [AgentActivityMapper] — no schema/DAO/tool changes.
 *
 * Undo reuses the exact same building blocks as the `undo_last` tool: [UndoExecutor] dispatches the
 * [com.andharness.ru.domain.undo.UndoAction] for the entry's kind, and, only after the inverse
 * reports success, the entry is removed from [UndoStore] (by id). A failed inverse leaves the entry
 * in the journal so the attempt can be repeated — the screen never claims a revert it did not do.
 *
 * Docs: `ViewModel`/`viewModelScope` and the `StateFlow` UI-state pattern —
 * https://developer.android.com/topic/architecture/ui-layer/state-production ; the journal contracts
 * are documented on `UndoStore` / `UndoExecutor`.
 */
@HiltViewModel
class AgentActivityViewModel @Inject constructor(
    private val auditRepository: ToolAuditRepository,
    private val undoStore: UndoStore,
    private val undoExecutor: UndoExecutor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentActivityUiState())
    val uiState: StateFlow<AgentActivityUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Reloads the report. Called on entry and by the top-bar refresh action. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true, message = null, messageIsError = false) }
        viewModelScope.launch {
            runCatching { readGroups() }
                .onSuccess { groups ->
                    _uiState.update { it.copy(groups = groups, isLoading = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = "Не удалось прочитать историю: " +
                                (error.message ?: error::class.simpleName),
                            messageIsError = true,
                        )
                    }
                }
        }
    }

    /**
     * Reverts one report row that carries an undo entry, mirroring `undo_last`: execute the inverse
     * first, remove the journal entry only on success, then reload so the badge/feed reflects the
     * real journal. A no-op for rows that are not undoable.
     */
    fun undo(item: AgentActivityItem) {
        val entry = item.undo ?: return
        if (_uiState.value.undoingAuditId != null) return
        _uiState.update {
            it.copy(undoingAuditId = item.auditId, message = null, messageIsError = false)
        }
        viewModelScope.launch {
            val outcome = undoExecutor.execute(entry.undoKind, entry.payload)
            val removed = if (outcome.success) undoStore.remove(entry.id) else false
            val message = when {
                outcome.success && removed -> "Отменено: ${entry.description}"
                outcome.success -> "Действие отменено, но запись в журнале уже отсутствовала."
                else -> "Не удалось отменить «${entry.description}»: ${outcome.message}. " +
                    "Запись сохранена в журнале — попытку можно повторить."
            }
            val refreshed = runCatching { readGroups() }.getOrNull()
            _uiState.update { state ->
                state.copy(
                    undoingAuditId = null,
                    message = message,
                    messageIsError = !outcome.success,
                    groups = refreshed ?: state.groups,
                )
            }
        }
    }

    /** Reads both journals and joins them into day groups. Throws only on an audit read failure. */
    private suspend fun readGroups(): List<AgentActivityGroup> {
        val audit = auditRepository.recent(AgentActivityMapper.MAX_ITEMS)
        val undo = undoStore.all()
        val items = AgentActivityMapper.build(audit, undo, AgentActivityMapper.MAX_ITEMS)
        return AgentActivityMapper.groupByDay(items, System.currentTimeMillis())
    }
}
