package com.andharness.ru.domain.undo

/**
 * One concrete inverse. [payload] is exactly [UndoEntry.payload]; implementations are registered
 * per [UndoKinds] value and live in `data/undo/UndoActions` (which owns the Android/repository
 * knowledge). Keeping it a `fun interface` lets tests register fakes in one line.
 */
fun interface UndoAction {
    suspend fun undo(payload: Map<String, String>): UndoOutcome
}
