package com.andharness.ru.data.tools.contacts

import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `contact_delete`: deletes the raw contacts behind an aggregate contact, with read-back. */
internal class ContactDeleteToolHandler(
    private val context: Context,
    private val io: ContactsProviderIo,
) : ToolHandler {
    override val name = "contact_delete"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val idArg = arguments["id"] as? JsonPrimitive
            if (idArg == null || idArg.content.isEmpty()) {
                return@withContext ToolResult(false, "Укажите id.")
            }
            val contactId = idArg.content
            // Deleting the RawContacts for this contact id removes the contact entirely.
            // Reference: https://developer.android.com/reference/android/provider/ContactsContract.RawContacts
            val deleted = context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId),
            )
            // Honest read-back: report success only when the aggregate no longer resolves.
            if (deleted > 0 && !io.contactExists(contactId)) {
                ToolResult(true, "Контакт $contactId удалён.")
            } else if (deleted > 0) {
                ToolResult(
                    false,
                    "Удалено raw-контактов: $deleted, но контакт $contactId всё ещё виден.",
                )
            } else {
                ToolResult(false, "Контакт $contactId не найден или не может быть удалён.")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
