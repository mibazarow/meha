package com.andharness.ru.domain.undo


/**
 * The closed set of inverses the app implements (task M3, slice 1). Each value names a concrete
 * action in `data/undo/UndoActions`; an [UndoEntry] whose kind is outside this set is not undoable.
 */
object UndoKinds {
    /** Restore the previous value of a `Settings.System` key (`settings_system_set`). */
    const val SETTINGS_RESTORE = "settings_restore"

    /** Delete the note created by `note_create`. */
    const val NOTE_DELETE = "note_delete"

    /** Delete the todo created by `todo_add`. */
    const val TODO_DELETE = "todo_delete"

    /** Delete the memory row created by `memory_save` (only when a new row was created). */
    const val MEMORY_DELETE = "memory_delete"

    /** Delete the scheduled task created by `schedule_create` (and cancel its work). */
    const val SCHEDULE_DELETE = "schedule_delete"

    /** Delete the raw contact created by `contact_create`. */
    const val CONTACT_DELETE = "contact_delete"

    /** Delete the calendar event created by `calendar_create`. */
    const val CALENDAR_DELETE = "calendar_delete"

    /** Every kind [com.andharness.ru.domain.undo.UndoExecutor] can dispatch. */
    val SUPPORTED: Set<String> = setOf(
        SETTINGS_RESTORE,
        NOTE_DELETE,
        TODO_DELETE,
        MEMORY_DELETE,
        SCHEDULE_DELETE,
        CONTACT_DELETE,
        CALENDAR_DELETE,
    )
}
