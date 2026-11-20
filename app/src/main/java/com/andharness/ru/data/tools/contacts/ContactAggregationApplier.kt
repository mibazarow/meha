package com.andharness.ru.data.tools.contacts

import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult

/**
 * Applies and verifies `ContactsContract.AggregationExceptions` rules for the merge/split tools
 * (`contact_merge`, `contact_split`).
 *
 * `ContactsContract.AggregationExceptions` supports ONLY query and update — insert and delete are
 * explicitly unsupported (an insert on this URI falls through the provider's legacy path and throws
 * `UnsupportedOperationException`). Its update path reads `TYPE`, `RAW_CONTACT_ID1` and
 * `RAW_CONTACT_ID2` from the values and performs an upsert (SQLite `REPLACE`) keyed on the
 * raw-contact pair, so one update both creates and changes the rule. The ids MUST be raw contact
 * ids, never aggregate contact ids.
 *
 * References:
 * - `ContactsContract.AggregationExceptions` (query/update only, TYPE / RAW_CONTACT_ID1 /
 *   RAW_CONTACT_ID2):
 *   https://developer.android.com/reference/android/provider/ContactsContract.AggregationExceptions
 * - `ContactsContract.RawContacts` (raw contact ids):
 *   https://developer.android.com/reference/android/provider/ContactsContract.RawContacts
 */
internal class ContactAggregationApplier(
    private val context: Context,
    private val io: ContactsProviderIo,
) {

    /**
     * Applies an aggregation rule between the raw contacts behind [contactId1] / [contactId2] and
     * re-reads it to confirm the write.
     */
    fun apply(contactId1: String, contactId2: String, type: Int): ToolResult {
        val resolution = ContactAggregation.resolveRawContactPair(
            contactId1 = contactId1,
            contactId2 = contactId2,
            rawContactIds1 = io.rawContactIdsForContact(contactId1),
            rawContactIds2 = io.rawContactIdsForContact(contactId2),
        )
        val pair = when (resolution) {
            is ContactAggregation.Result.Invalid -> return ToolResult(false, resolution.message)
            is ContactAggregation.Result.Resolved ->
                resolution.rawContactId1 to resolution.rawContactId2
        }
        val raw1 = pair.first
        val raw2 = pair.second

        val values = ContentValues(3).apply {
            put(ContactsContract.AggregationExceptions.TYPE, type)
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, raw1)
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, raw2)
        }
        try {
            context.contentResolver.update(
                ContactsContract.AggregationExceptions.CONTENT_URI,
                values,
                null,
                null,
            )
        } catch (e: SecurityException) {
            return ToolResult(
                false,
                "Провайдер контактов отклонил изменение правила агрегации: нет разрешения " +
                    "WRITE_CONTACTS.",
            )
        } catch (e: UnsupportedOperationException) {
            return ToolResult(
                false,
                "Провайдер контактов на этом устройстве не поддерживает изменение правил " +
                    "агрегации (доступно только чтение), поэтому слияние и разделение контактов " +
                    "недоступны.",
            )
        } catch (e: Exception) {
            return ToolResult(
                false,
                "Провайдер контактов отклонил изменение правила агрегации: ${e.message}",
            )
        }

        val verified = readAggregationType(raw1, raw2)
        val action = if (type == ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER) {
            "слияние"
        } else {
            "разделение"
        }
        return if (verified == type) {
            ToolResult(true, "Агрегация применена ($action): raw $raw1 и raw $raw2. Правило подтверждено.")
        } else {
            ToolResult(
                false,
                "Правило агрегации не подтвердилось после записи (получено: $verified). " +
                    "Проверьте, что оба контакта существуют и принадлежат одному пользователю.",
            )
        }
    }

    private fun readAggregationType(raw1: Long, raw2: Long): Int? {
        val selection =
            "(${ContactsContract.AggregationExceptions.RAW_CONTACT_ID1} = ? AND " +
                "${ContactsContract.AggregationExceptions.RAW_CONTACT_ID2} = ?) OR " +
                "(${ContactsContract.AggregationExceptions.RAW_CONTACT_ID1} = ? AND " +
                "${ContactsContract.AggregationExceptions.RAW_CONTACT_ID2} = ?)"
        val args = arrayOf(raw1.toString(), raw2.toString(), raw2.toString(), raw1.toString())
        var type: Int? = null
        context.contentResolver.query(
            ContactsContract.AggregationExceptions.CONTENT_URI,
            arrayOf(ContactsContract.AggregationExceptions.TYPE),
            selection,
            args,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                type = c.getInt(c.getColumnIndex(ContactsContract.AggregationExceptions.TYPE))
            }
        }
        return type
    }
}
