package com.andharness.ru.data.tools.contacts

import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `contact_photo_get`: returns the content URI of a contact photo, or reports none. */
internal class ContactPhotoGetToolHandler(
    private val context: Context,
) : ToolHandler {
    override val name = "contact_photo_get"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val idArg = arguments["contact_id"] as? JsonPrimitive
            if (idArg == null || idArg.content.isBlank()) {
                return@withContext ToolResult(false, "Укажите contact_id.")
            }
            val contactId = idArg.content.trim()
            // ContactsContract.Contacts.PHOTO_URI returns a content:// URI for the contact
            // photo, or null when the contact has no photo.
            // https://developer.android.com/reference/android/provider/ContactsContract.Contacts#PHOTO_URI
            val projection = arrayOf(ContactsContract.Contacts.PHOTO_URI)
            var photoUri: String? = null
            var found = false
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                ContactsContract.Contacts._ID + " = ?",
                arrayOf(contactId),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    found = true
                    val idx = c.getColumnIndex(ContactsContract.Contacts.PHOTO_URI)
                    if (idx >= 0 && !c.isNull(idx)) photoUri = c.getString(idx)
                }
            }
            when {
                !found -> ToolResult(false, "Контакт $contactId не найден.")
                photoUri != null -> ToolResult(true, "Фото контакта $contactId: $photoUri")
                else -> ToolResult(true, "У контакта $contactId нет фото.")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на чтение контактов (нужен READ_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
