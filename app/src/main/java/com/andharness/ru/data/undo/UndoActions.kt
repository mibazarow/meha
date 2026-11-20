package com.andharness.ru.data.undo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract.Events
import android.provider.ContactsContract.RawContacts
import android.provider.Settings
import com.andharness.ru.data.schedule.ScheduledTaskActions
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.undo.UndoAction
import com.andharness.ru.domain.undo.UndoKinds
import com.andharness.ru.domain.undo.UndoOutcome
import com.andharness.ru.domain.undo.UndoPayload

/**
 * The concrete inverses behind [UndoKinds]. Only safe, well-defined reversals are implemented;
 * anything without an exact inverse (SMS, calls, app uninstall, …) deliberately has no entry and is
 * therefore never recorded.
 *
 * The [settingsRestore] and [deleteById] helpers are pure (they take lambdas), so the tricky logic
 * is unit-tested on the JVM; the Android-bound [android] factory only supplies the real
 * `ContentResolver` / repositories.
 *
 * References (official docs):
 * - `Settings.System.putString(ContentResolver, String, String?)` — a `null` value deletes the key,
 *   and the boolean result reports success:
 *   https://developer.android.com/reference/android/provider/Settings.System#putString(android.content.ContentResolver,%20java.lang.String,%20java.lang.String)
 * - `ContentResolver.delete(Uri, String, String[])` returns the number of deleted rows:
 *   https://developer.android.com/reference/android/content/ContentResolver#delete(android.net.Uri,%20java.lang.String,%20java.lang.String[])
 * - `ContactsContract.RawContacts._ID` (deleting the raw contact removes the whole contact):
 *   https://developer.android.com/reference/android/provider/ContactsContract.RawContacts
 * - `CalendarContract.Events._ID` (deleting the event cascades its reminders):
 *   https://developer.android.com/reference/android/provider/CalendarContract.Events
 */
object UndoActions {

    /**
     * Restores the previous value of a `Settings.System` key. An absent payload value means the key
     * had no value before, so `null` is written back (which deletes it). The restore is verified by
     * a read-back, mirroring the honest verification of `settings_system_set`.
     */
    fun settingsRestore(
        get: (String) -> String?,
        put: (String, String?) -> Boolean,
    ): UndoAction = UndoAction { payload ->
        val key = payload[UndoPayload.KEY]?.takeIf { it.isNotBlank() }
            ?: return@UndoAction UndoOutcome(false, "Не удалось отменить: в записи нет ключа настройки.")
        val previous = payload[UndoPayload.PREVIOUS_VALUE]
        val restored = try {
            put(key, previous)
        } catch (_: Exception) {
            false
        }
        if (!restored) {
            return@UndoAction UndoOutcome(false, "Не удалось вернуть прежнее значение настройки «$key».")
        }
        val readBack = try {
            get(key)
        } catch (_: Exception) {
            null
        }
        if (readBack == previous) {
            val shown = previous ?: "(значение удалено)"
            UndoOutcome(true, "Настройка «$key» возвращена: $shown.")
        } else {
            UndoOutcome(
                false,
                "Не удалось подтвердить возврат настройки «$key»: сейчас «$readBack», " +
                    "ожидалось «$previous». Возможно, значение подменяет система или производитель.",
            )
        }
    }

    /**
     * Inverse for a "created row" action: deletes [what] by the id stored in the payload. Used for
     * notes, todos, memory rows and scheduled tasks (all repositories expose a boolean delete).
     */
    fun deleteById(what: String, delete: suspend (Long) -> Boolean): UndoAction = UndoAction { payload ->
        val id = payload[UndoPayload.ID]?.toLongOrNull()
            ?: return@UndoAction UndoOutcome(false, "Не удалось отменить: в записи нет id.")
        val removed = try {
            delete(id)
        } catch (_: Exception) {
            false
        }
        if (removed) {
            UndoOutcome(true, "Отменено: $what #$id удалён.")
        } else {
            UndoOutcome(false, "$what #$id не найден — возможно, уже удалён ранее.")
        }
    }

    /**
     * Inverse for a "created row" action backed by a [ContentResolver] URI (raw contacts, calendar
     * events): deletes by `_ID` and reports honestly when nothing matched.
     */
    fun deleteByUri(
        what: String,
        resolver: ContentResolver,
        uri: Uri,
        idColumn: String,
    ): UndoAction = UndoAction { payload ->
        val id = payload[UndoPayload.ID]?.toLongOrNull()
            ?: return@UndoAction UndoOutcome(false, "Не удалось отменить: в записи нет id.")
        val deleted = try {
            resolver.delete(uri, "$idColumn = ?", arrayOf(id.toString()))
        } catch (_: Exception) {
            -1
        }
        if (deleted > 0) {
            UndoOutcome(true, "Отменено: $what #$id удалён.")
        } else {
            UndoOutcome(false, "$what #$id не найден — возможно, уже удалён ранее.")
        }
    }

    /**
     * Production dispatch table. `schedule_delete` goes through [ScheduledTaskActions] so the
     * inverse also cancels the task's WorkManager work, not just its row.
     */
    fun android(
        context: Context,
        assistRepository: AssistRepository,
        scheduledTaskActions: ScheduledTaskActions,
    ): Map<String, UndoAction> = mapOf(
        UndoKinds.SETTINGS_RESTORE to settingsRestore(
            get = { key -> Settings.System.getString(context.contentResolver, key) },
            put = { key, value -> Settings.System.putString(context.contentResolver, key, value) },
        ),
        UndoKinds.NOTE_DELETE to deleteById("Заметка") { assistRepository.deleteNoteById(it) },
        UndoKinds.TODO_DELETE to deleteById("Задача") { assistRepository.deleteTodo(it) },
        UndoKinds.MEMORY_DELETE to deleteById("Запись памяти") { assistRepository.deleteMemory(it) },
        UndoKinds.SCHEDULE_DELETE to deleteById("Задача по расписанию") { scheduledTaskActions.delete(it) },
        UndoKinds.CONTACT_DELETE to deleteByUri(
            "Контакт",
            context.contentResolver,
            RawContacts.CONTENT_URI,
            RawContacts._ID,
        ),
        UndoKinds.CALENDAR_DELETE to deleteByUri(
            "Событие",
            context.contentResolver,
            Events.CONTENT_URI,
            Events._ID,
        ),
    )
}
