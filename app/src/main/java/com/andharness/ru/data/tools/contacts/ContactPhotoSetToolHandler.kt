package com.andharness.ru.data.tools.contacts

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Upper bound for a contact photo read into memory (avoids OOM on huge files). */
private const val MAX_PHOTO_BYTES = 8L * 1024L * 1024L

/** `contact_photo_set`: writes a photo file into a contact's photo Data row and verifies it. */
internal class ContactPhotoSetToolHandler(
    private val context: Context,
    private val io: ContactsProviderIo,
) : ToolHandler {
    override val name = "contact_photo_set"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val contactId = (arguments["contact_id"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (contactId.isEmpty()) {
                return@withContext ToolResult(false, "Укажите contact_id.")
            }
            val photoPath = (arguments["photo_path"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (photoPath.isEmpty()) {
                return@withContext ToolResult(false, "Укажите photo_path.")
            }
            val file = try {
                resolveSafePath(context.allowedToolRoots(), photoPath)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Недопустимый путь.")
            }
            if (!file.isFile) {
                return@withContext ToolResult(false, "Файл не найден: ${file.path}")
            }
            if (file.length() > MAX_PHOTO_BYTES) {
                return@withContext ToolResult(
                    false,
                    "Файл слишком большой (${file.length()} байт); максимум $MAX_PHOTO_BYTES байт.",
                )
            }
            val bytes = file.readBytes()
            val rawId = io.rawContactIdsForContact(contactId).firstOrNull()
                ?: return@withContext ToolResult(false, "Контакт $contactId не найден (нет raw-контакта).")

            // A photo is a single Data row with MIMETYPE photo whose BLOB is DATA15.
            // Replace the previous photo in the same transaction so no duplicates remain.
            // https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Photo
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation
                    .newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
                    )
                    .build(),
            )
            ops.add(
                ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.Data.DATA15, bytes)
                    .build(),
            )
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)

            val verified = io.findPhotoUri(contactId)
            if (verified != null) {
                ToolResult(true, "Фото контакта $contactId установлено (${bytes.size} байт).")
            } else {
                ToolResult(false, "Запись выполнена, но фото у контакта $contactId не обнаружено.")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS) или доступ к файлу.")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
