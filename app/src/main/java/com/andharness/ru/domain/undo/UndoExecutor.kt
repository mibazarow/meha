package com.andharness.ru.domain.undo

/**
 * Dispatch of an [UndoEntry.undoKind] to its [UndoAction]. Pure and dependency-free, so the whole
 * dispatch table is exercised on the JVM with fake actions; the production table is built in
 * `data/undo/UndoActions`.
 *
 * Honesty: an unknown kind returns a clear failure instead of pretending the action was reverted,
 * and a throwing action is converted to an honest failure message.
 */
class UndoExecutor(actions: Map<String, UndoAction>) {

    private val actions: Map<String, UndoAction> = actions.toMap()

    /** True when [kind] has a registered inverse. */
    fun supports(kind: String): Boolean = kind in actions

    /** Applies the inverse for [kind]. Never throws. */
    suspend fun execute(kind: String, payload: Map<String, String>): UndoOutcome {
        val action = actions[kind]
            ?: return UndoOutcome(
                false,
                "Это действие нельзя отменить автоматически (нет обратной операции для «$kind»).",
            )
        return try {
            action.undo(payload)
        } catch (e: Exception) {
            UndoOutcome(false, "Не удалось отменить действие: ${e.message}")
        }
    }
}
