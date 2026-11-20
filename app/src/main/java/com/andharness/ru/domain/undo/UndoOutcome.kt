package com.andharness.ru.domain.undo

/** Result of applying an inverse: [success] plus a Russian, user-facing [message]. */
data class UndoOutcome(val success: Boolean, val message: String)
