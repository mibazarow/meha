package com.andharness.ru.data.tools.contacts

import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `contact_star_set`: favourites/unfavourites a contact and verifies the write. */
internal class ContactStarSetToolHandler(
    private val context: Context,
) : ToolHandler {
    override val name = "contact_star_set"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val idArg = arguments["contact_id"] as? JsonPrimitive
            if (idArg == null || idArg.content.isBlank()) {
                return@withContext ToolResult(false, "Укажите contact_id.")
            }
            val contactId = idArg.content.trim()
            val starredArg = arguments["starred"] as? JsonPrimitive
            val starred = starredArg?.content?.toBooleanStrictOrNull() ?: true

            // ContactsContract.Contacts.STARRED is an int column: 1 = favourite, 0 = not.
            // https://developer.android.com/reference/android/provider/ContactsContract.Contacts#STARRED
            val values = ContentValues().apply {
                put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
            }
            val updated = context.contentResolver.update(
                ContactsContract.Contacts.CONTENT_URI,
                values,
                ContactsContract.Contacts._ID + " = ?",
                arrayOf(contactId),
            )
            if (updated <= 0) {
                return@withContext ToolResult(false, "Контакт $contactId не найден (ничего не изменено).")
            }
            // Re-read to verify the write actually took effect (honest post-verification).
            var verified: Int? = null
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.STARRED),
                ContactsContract.Contacts._ID + " = ?",
                arrayOf(contactId),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(ContactsContract.Contacts.STARRED)
                    if (idx >= 0) verified = c.getInt(idx)
                }
            }
            val expected = if (starred) 1 else 0
            if (verified == expected) {
                val label = if (starred) "добавлен в избранное" else "убран из избранного"
                ToolResult(true, "Контакт $contactId $label.")
            } else {
                ToolResult(
                    false,
                    "Изменение не подтвердилось: ожидалось starred=$expected, получено $verified.",
                )
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
