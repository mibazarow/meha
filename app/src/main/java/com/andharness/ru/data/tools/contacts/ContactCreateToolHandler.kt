package com.andharness.ru.data.tools.contacts

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.undo.UndoKinds
import com.andharness.ru.domain.undo.UndoPayload
import com.andharness.ru.domain.undo.UndoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `contact_create`: creates a contact (name + optional phone/email) as a batch insert. */
internal class ContactCreateToolHandler(
    private val context: Context,
    private val undoRecorder: UndoRecorder = UndoRecorder.NoOp,
) : ToolHandler {
    override val name = "contact_create"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val nameArg = (arguments["name"] as? JsonPrimitive)?.content.orEmpty().trim()
            val phoneArg = (arguments["phone"] as? JsonPrimitive)?.content.orEmpty().trim()
            val emailArg = (arguments["email"] as? JsonPrimitive)?.content.orEmpty().trim()
            if (nameArg.isEmpty()) {
                return@withContext ToolResult(false, "Укажите name.")
            }
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation
                    .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build(),
            )
            ops.add(
                ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, nameArg)
                    .build(),
            )
            if (phoneArg.isNotEmpty()) {
                ops.add(
                    ContentProviderOperation
                        .newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneArg)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build(),
                )
            }
            if (emailArg.isNotEmpty()) {
                ops.add(
                    ContentProviderOperation
                        .newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, emailArg)
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                        .build(),
                )
            }
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            // First result is the raw-contact insert; its URI carries the new raw id, which
            // is enough for `undo_last` to delete the whole contact again.
            val rawContactId = results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()
            if (rawContactId != null) {
                undoRecorder.record(
                    toolName = "contact_create",
                    description = "Создан контакт #$rawContactId: $nameArg",
                    undoKind = UndoKinds.CONTACT_DELETE,
                    payload = mapOf(UndoPayload.ID to rawContactId.toString()),
                )
            }
            ToolResult(true, "Контакт создан: $nameArg")
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
