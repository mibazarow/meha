package com.andharness.ru.data.tools.contacts

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.andharness.ru.domain.contacts.VCardContact

/**
 * Read/write helpers over the aggregate contacts provider shared by the contacts tools.
 *
 * Every method closes its own cursors and performs no writes except where the name says so; the
 * helpers exist to keep the per-tool handlers free of duplicated cursor plumbing. References:
 * - `ContactsContract.Contacts` / `ContactsContract.Data`:
 *   https://developer.android.com/reference/android/provider/ContactsContract.Contacts
 *   https://developer.android.com/reference/android/provider/ContactsContract.Data
 * - `ContactsContract.Contacts#CONTENT_FILTER_URI` (partial-name search):
 *   https://developer.android.com/reference/android/provider/ContactsContract.Contacts#CONTENT_FILTER_URI
 * - `ContactsContract.PhoneLookup.CONTENT_FILTER_URI`:
 *   https://developer.android.com/reference/android/provider/ContactsContract.PhoneLookup
 */
internal class ContactsProviderIo(private val context: Context) {

    /** Comma-separated argument -> trimmed entries, keeping empty slots so lists stay index-aligned. */
    fun splitList(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() } ?: emptyList()

    /** Builder for a Data insert that targets either an existing raw contact or a back-reference. */
    fun dataOpBuilder(
        rawId: Long? = null,
        backRef: Int? = null,
    ): ContentProviderOperation.Builder {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        if (backRef != null) {
            builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
        } else {
            builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId!!)
        }
        return builder
    }

    /**
     * Contact id whose display name equals [name] after trimming and case folding, or `null`.
     *
     * The provider's `CONTENT_FILTER_URI` yields *candidates* (it matches parts of the name); the
     * decisive comparison is done here in Kotlin because SQLite `LIKE` is only ASCII
     * case-insensitive and would treat «Иван» and «иван» as different. Reference:
     * https://developer.android.com/reference/android/provider/ContactsContract.Contacts#CONTENT_FILTER_URI
     */
    fun findContactIdByName(name: String): String? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI,
            Uri.encode(wanted),
        )
        var id: String? = null
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            while (c.moveToNext()) {
                val candidate = if (nameIdx >= 0) c.getString(nameIdx)?.trim() else null
                if (candidate != null && candidate.equals(wanted, ignoreCase = true)) {
                    id = if (idIdx >= 0) c.getString(idIdx) else null
                    break
                }
            }
        }
        return id
    }

    /**
     * `Data._ID` of the first (lowest-id) row of [mimeType] anywhere in the aggregate
     * [contactId], or `null` when that type is absent. Sorting by `Data._ID` makes the choice
     * deterministic, so contact_update targets ONE row instead of overwriting every row of the
     * type. Reference:
     * https://developer.android.com/reference/android/provider/ContactsContract.Data
     */
    fun firstDataRowId(contactId: String, mimeType: String): Long? {
        var dataId: Long? = null
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, mimeType),
            "${ContactsContract.Data._ID} ASC",
        )?.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.Data._ID)
            if (c.moveToFirst() && idIdx >= 0) dataId = c.getLong(idIdx)
        }
        return dataId
    }

    /** Non-blank values of [valueColumn] for every [mimeType] row of [contactId], in row order. */
    fun dataValuesForContact(
        contactId: String,
        mimeType: String,
        valueColumn: String,
    ): List<String> {
        val values = ArrayList<String>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(valueColumn),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, mimeType),
            "${ContactsContract.Data._ID} ASC",
        )?.use { c ->
            val idx = c.getColumnIndex(valueColumn)
            if (idx >= 0) {
                while (c.moveToNext()) {
                    val value = c.getString(idx)
                    if (!value.isNullOrBlank()) values.add(value)
                }
            }
        }
        return values
    }

    /** First phone number of [contactId] via the Phone data view, or `null` (cursor closed). */
    fun firstPhoneForContact(contactId: String): String? {
        var number: String? = null
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            "${ContactsContract.Data._ID} ASC",
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (idx >= 0) number = c.getString(idx)
            }
        }
        return number?.takeIf { it.isNotBlank() }
    }

    /** Aggregate display name for [contactId], or `null` when the contact does not resolve. */
    fun contactDisplayName(contactId: String): String? {
        var name: String? = null
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx)
            }
        }
        return name
    }

    /** Whether the aggregate contact still exists after a write (used for honest read-back). */
    fun contactExists(contactId: String): Boolean {
        var exists = false
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { c -> exists = c.moveToFirst() }
        return exists
    }

    fun rawContactIdsForContact(contactId: String): List<Long> {
        val ids = ArrayList<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { c ->
            val idx = c.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
            while (c.moveToNext()) {
                val raw = c.getLong(idx)
                if (!ids.contains(raw)) ids.add(raw)
            }
        }
        return ids
    }

    fun contactHasData(contactId: String, mimeType: String, value: String): Boolean {
        var found = false
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DATA1),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, mimeType),
            null,
        )?.use { c ->
            val idx = c.getColumnIndex(ContactsContract.Data.DATA1)
            while (c.moveToNext()) {
                val existing = c.getString(idx) ?: continue
                if (normalizeValue(existing) == normalizeValue(value)) {
                    found = true
                    break
                }
            }
        }
        return found
    }

    /** Digits-only comparison for phone numbers; case-insensitive fallback for e-mail. */
    fun normalizeValue(value: String): String {
        val digits = value.filter { it.isDigit() || it == '+' }
        return digits.ifEmpty { value.trim().lowercase() }
    }

    fun findPhotoUri(contactId: String): String? {
        var uri: String? = null
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.PHOTO_URI),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(ContactsContract.Contacts.PHOTO_URI)
                if (idx >= 0 && !c.isNull(idx)) uri = c.getString(idx)
            }
        }
        return uri
    }

    /** Aggregate contact id behind a phone [number] via PhoneLookup, or `null`. */
    fun contactIdByNumber(number: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        var id: String? = null
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup._ID),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(ContactsContract.PhoneLookup._ID)
                if (idx >= 0) id = c.getString(idx)
            }
        }
        return id
    }

    /** Every birthday row joined with its contact's display name. */
    fun birthdayEntries(): List<ContactBirthdays.Entry> {
        // Read all birthday rows first (cursor closed) and only then resolve display names, so no
        // second query runs while the first cursor is still open.
        val rows = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.CommonDataKinds.Event.START_DATE,
            ),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
            arrayOf(
                ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString(),
            ),
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val dateIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE)
            while (c.moveToNext()) {
                if (idIdx < 0 || dateIdx < 0) continue
                val contactId = c.getString(idIdx) ?: continue
                val date = c.getString(dateIdx) ?: continue
                if (date.isBlank()) continue
                rows.add(contactId to date)
            }
        }
        val names = HashMap<String, String>()
        return rows.map { (contactId, date) ->
            val name = names.getOrPut(contactId) {
                contactDisplayName(contactId)?.takeIf { it.isNotBlank() } ?: "(без имени)"
            }
            ContactBirthdays.Entry(contactId, name, date)
        }
    }

    /**
     * Builds vCard payloads from the aggregate contacts provider: one [VCardContact] per contact,
     * carrying the display/structured name and every phone number and e-mail address. With
     * [contactId] `null` all contacts are returned; otherwise only that contact's rows.
     *
     * The values come from `ContactsContract.Data` rows filtered by MIMETYPE: `StructuredName`
     * (DATA1 = DISPLAY_NAME, DATA2 = GIVEN_NAME, DATA3 = FAMILY_NAME), `Phone` (DATA1 = NUMBER) and
     * `Email` (DATA1 = ADDRESS). Rows are ordered by `CONTACT_ID` then `_ID`, so the per-contact
     * grouping is stable and cheap (one query, no N+1).
     * References:
     * - https://developer.android.com/reference/android/provider/ContactsContract.Data
     * - https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.StructuredName
     * - `ContactsContract.Contacts.CONTENT_VCARD_URI` (the platform's own vCard URI):
     *   https://developer.android.com/reference/android/provider/ContactsContract.Contacts#CONTENT_VCARD_URI
     */
    fun vCards(contactId: String?): List<VCardContact> {
        data class Row(
            val contactId: String,
            val mimeType: String,
            val data1: String?,
            val data2: String?,
            val data3: String?,
        )

        val rows = ArrayList<Row>()
        val selection = if (contactId != null) "${ContactsContract.Data.CONTACT_ID} = ?" else null
        val args = if (contactId != null) arrayOf(contactId) else null
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
            ),
            selection,
            args,
            "${ContactsContract.Data.CONTACT_ID} ASC, ${ContactsContract.Data._ID} ASC",
        )?.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val mimeIdx = c.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val d1Idx = c.getColumnIndex(ContactsContract.Data.DATA1)
            val d2Idx = c.getColumnIndex(ContactsContract.Data.DATA2)
            val d3Idx = c.getColumnIndex(ContactsContract.Data.DATA3)
            while (c.moveToNext()) {
                if (idIdx < 0 || mimeIdx < 0) continue
                val id = c.getString(idIdx) ?: continue
                rows.add(
                    Row(
                        contactId = id,
                        mimeType = c.getString(mimeIdx) ?: continue,
                        data1 = if (d1Idx >= 0) c.getString(d1Idx) else null,
                        data2 = if (d2Idx >= 0) c.getString(d2Idx) else null,
                        data3 = if (d3Idx >= 0) c.getString(d3Idx) else null,
                    ),
                )
            }
        }

        val cards = ArrayList<VCardContact>()
        val names = HashMap<String, Triple<String?, String?, String?>>()
        val phones = HashMap<String, MutableList<String>>()
        val emails = HashMap<String, MutableList<String>>()
        val order = LinkedHashSet<String>()
        for (row in rows) {
            order.add(row.contactId)
            when (row.mimeType) {
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                    names[row.contactId] = Triple(row.data1, row.data2, row.data3)
                }
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                    row.data1?.takeIf { it.isNotBlank() }
                        ?.let { phones.getOrPut(row.contactId) { ArrayList() }.add(it) }
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                    row.data1?.takeIf { it.isNotBlank() }
                        ?.let { emails.getOrPut(row.contactId) { ArrayList() }.add(it) }
            }
        }
        for (id in order) {
            val (display, given, family) = names[id] ?: Triple(null, null, null)
            val fullName = display?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(given?.takeIf { it.isNotBlank() }, family?.takeIf { it.isNotBlank() })
                    .joinToString(" ")
                    .takeIf { it.isNotBlank() }
                ?: "(без имени)"
            cards.add(
                VCardContact(
                    fullName = fullName,
                    givenName = given,
                    familyName = family,
                    phones = phones[id] ?: emptyList(),
                    emails = emails[id] ?: emptyList(),
                ),
            )
        }
        return cards
    }
}
