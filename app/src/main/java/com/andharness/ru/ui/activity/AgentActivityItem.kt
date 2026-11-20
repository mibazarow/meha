package com.andharness.ru.ui.activity

import com.andharness.ru.domain.agent.ToolLabels
import com.andharness.ru.domain.undo.UndoEntry

/**
 * One row of the human-readable «Что агент сделал» report: a tool-call audit entry ([auditId] is the
 * `ToolAuditEntity` row id, which is unique) plus, when the action is reversible, the live
 * [UndoEntry] that can revert it.
 *
 * [title] is the human tool name from [ToolLabels]; [argsPreview] is the already-redacted argument
 * preview stored in the audit row (never raw secrets). [isUndoable] is true only when a supported
 * undo entry was matched — an action stays in the report even after it is undone, but then it is no
 * longer undoable (the audit log is append-only), which is the honest view.
 */
data class AgentActivityItem(
    val auditId: Long,
    val at: Long,
    val tool: String,
    val title: String,
    val success: Boolean,
    val argsPreview: String,
    val undo: UndoEntry?,
) {
    /** True when this action has a live, supported inverse and can therefore be undone from here. */
    val isUndoable: Boolean get() = undo != null
}
