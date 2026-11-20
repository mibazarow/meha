package com.andharness.ru.ui.activity


/**
 * State of the «Что агент сделал» screen.
 *
 * [groups] is the bounded, newest-first report split into day groups. [undoingAuditId] is the row id
 * of an in-flight undo (it disables that one button and blocks a second concurrent undo). [message]
 * carries an honest status/failure line, coloured by [messageIsError], and is cleared on refresh.
 */
data class AgentActivityUiState(
    val groups: List<AgentActivityGroup> = emptyList(),
    val isLoading: Boolean = true,
    val undoingAuditId: Long? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
)
