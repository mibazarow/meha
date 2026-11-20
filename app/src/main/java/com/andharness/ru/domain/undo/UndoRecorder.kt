package com.andharness.ru.domain.undo


/**
 * Narrow write surface handlers use to record an undoable action. Handlers depend on this (domain)
 * interface only, so they stay trivially constructed in tests; the real implementation is the
 * file-backed `UndoStore`. Default [NoOp] keeps every existing construction site compiling.
 */
interface UndoRecorder {

    /** Records a reversible action. Best-effort: a failed write must never fail the tool result. */
    fun record(toolName: String, description: String, undoKind: String, payload: Map<String, String>)

    /** Recorder that drops every entry — for tests and call sites that pass no undo store. */
    object NoOp : UndoRecorder {
        override fun record(
            toolName: String,
            description: String,
            undoKind: String,
            payload: Map<String, String>,
        ) = Unit
    }
}
