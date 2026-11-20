package com.andharness.ru.domain.undo

import kotlinx.serialization.Serializable

/**
 * One reversible action the app knows how to undo (M3 «Доверие»). [undoKind] names the inverse
 * operation (see [UndoKinds]) and [payload] carries exactly the data needed to apply it.
 *
 * Honesty rule: an entry exists ONLY when the app actually knows the inverse. Actions with no
 * well-defined reversal (SMS, calls, app uninstall, …) are never recorded.
 *
 * Persistence lives in `data/undo/UndoStore`; this model is pure and JVM-testable.
 */

@Serializable
data class UndoEntry(
    /** Stable identity of the journal row (used by `remove(id)` / `undo_list`). */
    val id: String,
    /** Tool whose successful run produced this undoable action, e.g. `note_create`. */
    val toolName: String,
    /** Human-readable (Russian) description of what was done, shown by `undo_list`. */
    val description: String,
    /** Epoch millis when the action was recorded. */
    val timestamp: Long,
    /** Which inverse to apply; one of [UndoKinds.SUPPORTED]. */
    val undoKind: String,
    /** Data required by the inverse (a JSON object on disk). */
    val payload: Map<String, String> = emptyMap(),
)
