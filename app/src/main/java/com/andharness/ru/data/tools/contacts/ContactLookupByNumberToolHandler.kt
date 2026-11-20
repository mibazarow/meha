package com.andharness.ru.data.tools.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `contact_lookup_by_number`: resolves a raw phone number to matching contacts via PhoneLookup. */
internal class ContactLookupByNumberToolHandler(
    private val context: Context,
) : ToolHandler {
    override val name = "contact_lookup_by_number"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val number = ((arguments["number"] as? JsonPrimitive)?.content ?: "").trim()
            if (number.isEmpty()) {
                return@withContext ToolResult(false, "Укажите number.")
            }
            // PhoneLookup.CONTENT_FILTER_URI resolves a raw phone number to the matching
            // contact(s). Uri.withAppendedPath(CONTENT_FILTER_URI, Uri.encode(number)).
            // Reference: ContactsContract.PhoneLookup —
            // https://developer.android.com/reference/android/provider/ContactsContract.PhoneLookup
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_URI,
            )
            val lines = ArrayList<String>()
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.PhoneLookup._ID)
                val nameIdx = c.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                val photoIdx = c.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)
                while (c.moveToNext()) {
                    val id = c.getString(idIdx)
                    val name = c.getString(nameIdx)
                    val photo = c.getString(photoIdx)
                    lines.add(
                        "id=$id | имя: ${name ?: "(без имени)"} | фото: ${photo ?: "(нет)"}",
                    )
                }
            }
            if (lines.isEmpty()) {
                ToolResult(true, "Контакт с номером «$number» не найден.")
            } else {
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на чтение контактов (нужен READ_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
