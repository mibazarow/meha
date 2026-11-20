package com.andharness.ru.data.tools.contacts

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `contact_update`: updates exactly one row per requested field and reports the read-back. */
internal class ContactUpdateToolHandler(
    private val context: Context,
    private val io: ContactsProviderIo,
) : ToolHandler {
    override val name = "contact_update"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val nameField = (arguments["name"] as? JsonPrimitive)?.content.orEmpty().trim()
            val phoneField = (arguments["phone"] as? JsonPrimitive)?.content.orEmpty().trim()
            val emailField = (arguments["email"] as? JsonPrimitive)?.content.orEmpty().trim()
            if (nameField.isEmpty() && phoneField.isEmpty() && emailField.isEmpty()) {
                return@withContext ToolResult(true, "Нет полей для обновления")
            }
            val idArg = arguments["id"] as? JsonPrimitive
            if (idArg == null) {
                return@withContext ToolResult(false, "Укажите id.")
            }
            val contactId = idArg.content
            val rawIds = ArrayList<String>()
            val resolver = context.contentResolver
            val projection = arrayOf(ContactsContract.Data.RAW_CONTACT_ID)
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                "${ContactsContract.Data.CONTACT_ID} = ?",
                arrayOf(contactId),
                null,
            )?.use { c ->
                val idx = c.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
                while (c.moveToNext()) {
                    val raw = c.getString(idx)
                    if (!rawIds.contains(raw)) rawIds.add(raw)
                }
            }
            if (rawIds.isEmpty()) {
                return@withContext ToolResult(false, "Контакт $contactId не найден.")
            }
            // Update exactly ONE existing row per requested type (the lowest Data._ID of
            // that MIMETYPE anywhere in the aggregate) instead of overwriting every row;
            // when the type is absent, INSERT one on the first raw contact instead of
            // silently matching 0 rows. The Data table is the generic store whose DATA1
            // holds the value and DATA2 the type. References:
            // https://developer.android.com/reference/android/provider/ContactsContract.Data
            // https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Phone
            val firstRawId = rawIds.first()
            val ops = ArrayList<ContentProviderOperation>()

            if (nameField.isNotEmpty()) {
                val existingRow = io.firstDataRowId(contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                if (existingRow != null) {
                    ops.add(
                        ContentProviderOperation
                            .newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(existingRow.toString()))
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, nameField)
                            .build(),
                    )
                } else {
                    ops.add(
                        ContentProviderOperation
                            .newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, firstRawId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, nameField)
                            .build(),
                    )
                }
            }
            if (phoneField.isNotEmpty()) {
                val existingRow = io.firstDataRowId(contactId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                if (existingRow != null) {
                    ops.add(
                        ContentProviderOperation
                            .newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(existingRow.toString()))
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneField)
                            .build(),
                    )
                } else {
                    ops.add(
                        ContentProviderOperation
                            .newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, firstRawId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneField)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                            .build(),
                    )
                }
            }
            if (emailField.isNotEmpty()) {
                val existingRow = io.firstDataRowId(contactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                if (existingRow != null) {
                    ops.add(
                        ContentProviderOperation
                            .newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(existingRow.toString()))
                            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, emailField)
                            .build(),
                    )
                } else {
                    ops.add(
                        ContentProviderOperation
                            .newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, firstRawId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, emailField)
                            .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                            .build(),
                    )
                }
            }
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)

            // Read the contact back and report what it ACTUALLY holds now, so an
            // ineffective write can never masquerade as success.
            val phonesNow = io.dataValuesForContact(
                contactId,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val emailsNow = io.dataValuesForContact(
                contactId,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
            )
            val nameNow = io.contactDisplayName(contactId)?.trim()
            val missing = ArrayList<String>()
            if (phoneField.isNotEmpty() &&
                phonesNow.none { io.normalizeValue(it) == io.normalizeValue(phoneField) }
            ) {
                missing.add("телефон")
            }
            if (emailField.isNotEmpty() &&
                emailsNow.none { io.normalizeValue(it) == io.normalizeValue(emailField) }
            ) {
                missing.add("email")
            }
            val phonesText = phonesNow.ifEmpty { listOf("(нет)") }.joinToString(", ")
            val emailsText = emailsNow.ifEmpty { listOf("(нет)") }.joinToString(", ")
            val snapshot = "имя «${nameNow ?: "(нет)"}», телефоны: $phonesText, email: $emailsText"
            if (missing.isEmpty()) {
                ToolResult(true, "Контакт $contactId обновлён. Сейчас: $snapshot.")
            } else {
                ToolResult(
                    false,
                    "Контакт $contactId обновлён не полностью (не подтверждено: ${missing.joinToString(", ")}). Сейчас: $snapshot.",
                )
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
