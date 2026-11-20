package com.andharness.ru.data.tools.contacts

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.data.tools.files.FileToolIo
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.contacts.VCardContact
import com.andharness.ru.domain.contacts.VCardParser
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * `contact_import_vcf`: reads a vCard 3.0 file and inserts its contacts into the local phone book.
 *
 * Parsing is done by the pure [VCardParser] (so it is JVM-tested and never touches Android); this
 * handler only performs the provider writes. Each card is inserted as a local raw contact
 * (`ACCOUNT_TYPE`/`ACCOUNT_NAME` = null, the documented "local account" insert) plus its
 * `StructuredName` / `Phone` / `Email` data rows, in one batch per card so a single bad card is
 * counted as skipped instead of aborting the whole import. Unsupported vCard properties are ignored
 * and that is stated honestly in the result.
 *
 * References:
 * - `ContactsContract.RawContacts` / `ContactsContract.Data` (batch insert, data columns):
 *   https://developer.android.com/reference/android/provider/ContactsContract.RawContacts
 *   https://developer.android.com/reference/android/provider/ContactsContract.Data
 * - `ContentProviderOperation.newInsert` / `ContentResolver.applyBatch`:
 *   https://developer.android.com/reference/android/content/ContentProviderOperation
 * - RFC 2426 (vCard 3.0): https://datatracker.ietf.org/doc/html/rfc2426
 */
internal class ContactImportVcfToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "contact_import_vcf"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val rawPath = (arguments["path"] as? JsonPrimitive)?.content?.trim()
            if (rawPath.isNullOrEmpty()) {
                return@withContext ToolResult(false, "Укажите path к файлу .vcf.")
            }
            val file = File(FileToolIo(context).resolvePath(rawPath))
            if (!file.isFile) {
                return@withContext ToolResult(false, "Файл не найден: ${file.absolutePath}")
            }
            val parsed = VCardParser.parse(file.readText(Charsets.UTF_8))
            if (parsed.contacts.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "В файле нет распознанных контактов (пропущено карточек: ${parsed.skipped}).",
                )
            }
            var imported = 0
            var skipped = parsed.skipped
            for (card in parsed.contacts) {
                try {
                    insertCard(card)
                    imported++
                } catch (e: SecurityException) {
                    throw e
                } catch (e: Exception) {
                    skipped++
                }
            }
            val skipNote = if (skipped > 0) {
                " Пропущено карточек: $skipped (пустые, повреждённые или не поддавшиеся записи)."
            } else {
                ""
            }
            ToolResult(
                imported > 0,
                "Импортировано контактов: $imported из ${parsed.contacts.size}.$skipNote " +
                    "Неподдерживаемые свойства vCard (адрес, организация, день рождения и т. п.) игнорируются.",
            )
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на запись контактов (нужен WRITE_CONTACTS).")
        } catch (e: IllegalArgumentException) {
            ToolResult(false, e.message ?: "Недопустимый путь.")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    /** Inserts one parsed card as a local raw contact plus its name/phone/email data rows. */
    private fun insertCard(card: VCardContact) {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation
                .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
        )
        if (card.fullName.isNotBlank() || !card.givenName.isNullOrBlank() || !card.familyName.isNullOrBlank()) {
            val name = ContentProviderOperation
                .newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                )
            if (card.fullName.isNotBlank()) {
                name.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, card.fullName)
            }
            if (!card.givenName.isNullOrBlank()) {
                name.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, card.givenName)
            }
            if (!card.familyName.isNullOrBlank()) {
                name.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, card.familyName)
            }
            ops.add(name.build())
        }
        card.phones.filter { it.isNotBlank() }.forEach { phone ->
            ops.add(
                ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                    )
                    .build(),
            )
        }
        card.emails.filter { it.isNotBlank() }.forEach { email ->
            ops.add(
                ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                    .withValue(
                        ContactsContract.CommonDataKinds.Email.TYPE,
                        ContactsContract.CommonDataKinds.Email.TYPE_HOME,
                    )
                    .build(),
            )
        }
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }
}
