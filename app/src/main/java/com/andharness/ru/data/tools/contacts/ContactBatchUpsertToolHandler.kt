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

/** `contact_batch_upsert`: creates/extends several contacts in one provider transaction. */
internal class ContactBatchUpsertToolHandler(
    private val context: Context,
    private val io: ContactsProviderIo,
) : ToolHandler {
    override val name = "contact_batch_upsert"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val names = io.splitList((arguments["names"] as? JsonPrimitive)?.content)
            if (names.none { it.isNotEmpty() }) {
                return@withContext ToolResult(false, "Укажите names — имена контактов через запятую.")
            }
            val phones = io.splitList((arguments["phones"] as? JsonPrimitive)?.content)
            val emails = io.splitList((arguments["emails"] as? JsonPrimitive)?.content)

            val ops = ArrayList<ContentProviderOperation>()
            val touchedNames = ArrayList<String>()
            var created = 0
            var extended = 0
            var unchanged = 0

            for (i in names.indices) {
                val name = names[i]
                if (name.isEmpty()) continue
                touchedNames.add(name)
                val phone = phones.getOrNull(i)?.trim().orEmpty()
                val email = emails.getOrNull(i)?.trim().orEmpty()
                val existingId = io.findContactIdByName(name)
                if (existingId == null) {
                    // One RawContact insert followed by Data rows that reference it by
                    // back-reference (index of the raw-contact op) — the canonical batch
                    // pattern. Reference:
                    // https://developer.android.com/reference/android/provider/ContactsContract.RawContacts
                    val backRef = ops.size
                    ops.add(
                        ContentProviderOperation
                            .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                            .build(),
                    )
                    ops.add(
                        io.dataOpBuilder(backRef = backRef)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                            .build(),
                    )
                    if (phone.isNotEmpty()) {
                        ops.add(
                            io.dataOpBuilder(backRef = backRef)
                                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                                .build(),
                        )
                    }
                    if (email.isNotEmpty()) {
                        ops.add(
                            io.dataOpBuilder(backRef = backRef)
                                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                                .build(),
                        )
                    }
                    created++
                } else {
                    // Only the FIRST raw contact of the aggregate is used as the target for
                    // newly added phone/email rows; further raw contacts are left untouched.
                    val rawId = io.rawContactIdsForContact(existingId).firstOrNull()
                    if (rawId == null) {
                        unchanged++
                        continue
                    }
                    var added = false
                    if (phone.isNotEmpty() &&
                        !io.contactHasData(existingId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, phone)
                    ) {
                        ops.add(
                            io.dataOpBuilder(rawId = rawId)
                                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                                .build(),
                        )
                        added = true
                    }
                    if (email.isNotEmpty() &&
                        !io.contactHasData(existingId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, email)
                    ) {
                        ops.add(
                            io.dataOpBuilder(rawId = rawId)
                                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                                .build(),
                        )
                        added = true
                    }
                    if (added) extended++ else unchanged++
                }
            }

            if (ops.isEmpty()) {
                return@withContext ToolResult(true, "Изменений не требуется: контакты уже актуальны.")
            }
            // applyBatch runs every operation in ONE provider transaction.
            // https://developer.android.com/reference/android/content/ContentResolver#applyBatch(java.lang.String,%20java.util.ArrayList%3Candroid.content.ContentProviderOperation%3E)
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val missing = touchedNames.filter { io.findContactIdByName(it) == null }
            if (missing.isNotEmpty()) {
                ToolResult(false, "Пакет применён, но не найдены после записи: ${missing.joinToString(", ")}.")
            } else {
                ToolResult(
                    true,
                    "Пакет применён одной транзакцией: создано $created, дополнено $extended, без изменений $unchanged.",
                )
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
