package com.andharness.ru.data.tools.contacts

import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `contact_photo_clear`: deletes a contact's photo Data rows and verifies the removal. */
internal class ContactPhotoClearToolHandler(
    private val context: Context,
    private val io: ContactsProviderIo,
) : ToolHandler {
    override val name = "contact_photo_clear"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val contactId = (arguments["contact_id"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (contactId.isEmpty()) {
                return@withContext ToolResult(false, "Укажите contact_id.")
            }
            val rawIds = io.rawContactIdsForContact(contactId)
            if (rawIds.isEmpty()) {
                return@withContext ToolResult(false, "Контакт $contactId не найден.")
            }
            var deleted = 0
            for (rawId in rawIds) {
                deleted += context.contentResolver.delete(
                    ContactsContract.Data.CONTENT_URI,
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
                )
            }
            val verified = io.findPhotoUri(contactId)
            when {
                verified != null -> ToolResult(false, "Не удалось удалить фото контакта $contactId.")
                deleted == 0 -> ToolResult(false, "У контакта $contactId фото не было (нечего удалять).")
                else -> ToolResult(true, "Фото контакта $contactId удалено (строк: $deleted).")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
