package com.andharness.ru.data.tools.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * Provider reads for the contact-group tools (`contact_groups_list`, `contact_group_create`,
 * `contact_group_assign`): group rows with their owning accounts and the group-membership rows of a
 * contact.
 *
 * References:
 * - `ContactsContract.Groups` ACCOUNT_TYPE / ACCOUNT_NAME (from `SyncColumns`):
 *   https://developer.android.com/reference/android/provider/ContactsContract.Groups
 * - `ContactsContract.RawContacts` ACCOUNT_TYPE / ACCOUNT_NAME:
 *   https://developer.android.com/reference/android/provider/ContactsContract.RawContacts
 * - `ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID` (data1):
 *   https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.GroupMembership
 */
internal class ContactGroupIo(private val context: Context) {

    /**
     * The account a new group is created in when the caller did not name one: the account of an
     * existing group, then the account of any existing raw contact. `null` means no account was
     * discoverable and the group is created locally.
     */
    fun defaultGroupAccount(): ContactGroups.Account? {
        val groupAccount = readAccount(
            ContactsContract.Groups.CONTENT_URI,
            ContactsContract.Groups.ACCOUNT_TYPE,
            ContactsContract.Groups.ACCOUNT_NAME,
        )
        if (groupAccount != null) return groupAccount
        return readAccount(
            ContactsContract.RawContacts.CONTENT_URI,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.ACCOUNT_NAME,
        )
    }

    /** First row of [uri] whose account type/name are both non-blank, or `null`. */
    private fun readAccount(uri: Uri, typeColumn: String, nameColumn: String): ContactGroups.Account? {
        context.contentResolver.query(
            uri,
            arrayOf(typeColumn, nameColumn),
            "$typeColumn IS NOT NULL AND $nameColumn IS NOT NULL",
            null,
            null,
        )?.use { c ->
            val typeIdx = c.getColumnIndex(typeColumn)
            val nameIdx = c.getColumnIndex(nameColumn)
            if (c.moveToFirst()) {
                val type = if (typeIdx >= 0) c.getString(typeIdx) else null
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                if (!type.isNullOrBlank() && !name.isNullOrBlank()) {
                    return ContactGroups.Account(type, name)
                }
            }
        }
        return null
    }

    /** Every group with its id, title and owning account, ordered by title. */
    fun allGroups(): List<ContactGroups.GroupRef> {
        val groups = ArrayList<ContactGroups.GroupRef>()
        context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(
                ContactsContract.Groups._ID,
                ContactsContract.Groups.TITLE,
                ContactsContract.Groups.ACCOUNT_TYPE,
                ContactsContract.Groups.ACCOUNT_NAME,
            ),
            null,
            null,
            ContactsContract.Groups.TITLE + " ASC",
        )?.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.Groups._ID)
            val titleIdx = c.getColumnIndex(ContactsContract.Groups.TITLE)
            val typeIdx = c.getColumnIndex(ContactsContract.Groups.ACCOUNT_TYPE)
            val nameIdx = c.getColumnIndex(ContactsContract.Groups.ACCOUNT_NAME)
            while (c.moveToNext()) {
                if (idIdx < 0) continue
                groups.add(
                    ContactGroups.GroupRef(
                        id = c.getLong(idIdx),
                        title = if (titleIdx >= 0) c.getString(titleIdx) else null,
                        accountType = if (typeIdx >= 0) c.getString(typeIdx) else null,
                        accountName = if (nameIdx >= 0) c.getString(nameIdx) else null,
                    ),
                )
            }
        }
        return groups
    }

    /** Stored title of group [groupId], or `null` when the group does not resolve. */
    fun groupTitle(groupId: Long): String? {
        var title: String? = null
        context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups._ID} = ?",
            arrayOf(groupId.toString()),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(ContactsContract.Groups.TITLE)
                if (idx >= 0) title = c.getString(idx)
            }
        }
        return title
    }

    /** GROUP_ROW_ID values of every group-membership row of [contactId]. */
    fun groupMembershipIds(contactId: String): List<Long> {
        val ids = ArrayList<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID)
            if (idx >= 0) {
                while (c.moveToNext()) {
                    if (!c.isNull(idx)) ids.add(c.getLong(idx))
                }
            }
        }
        return ids
    }

    /** Raw contacts of [contactId] with their owning account (a group applies per account). */
    fun rawContactsWithAccounts(contactId: String): List<ContactGroups.RawContactRef> {
        val refs = ArrayList<ContactGroups.RawContactRef>()
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.ACCOUNT_NAME,
            ),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.RawContacts._ID)
            val typeIdx = c.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
            val nameIdx = c.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
            while (c.moveToNext()) {
                if (idIdx < 0) continue
                refs.add(
                    ContactGroups.RawContactRef(
                        rawContactId = c.getLong(idIdx),
                        accountType = if (typeIdx >= 0) c.getString(typeIdx) else null,
                        accountName = if (nameIdx >= 0) c.getString(nameIdx) else null,
                    ),
                )
            }
        }
        return refs
    }
}
