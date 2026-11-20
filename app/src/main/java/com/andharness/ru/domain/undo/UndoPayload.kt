package com.andharness.ru.domain.undo


/** Keys used inside [UndoEntry.payload]. */
object UndoPayload {
    /** `Settings.System` key to restore. */
    const val KEY = "key"

    /**
     * The value the key held before the write. ABSENT means the key had no value (the inverse
     * removes it by writing `null`, which is how `Settings.System.putString` deletes a setting).
     */
    const val PREVIOUS_VALUE = "previousValue"

    /** Row id (note/todo/memory/schedule/raw-contact/event) to delete. */
    const val ID = "id"
}
