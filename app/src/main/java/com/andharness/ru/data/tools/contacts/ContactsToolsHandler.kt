package com.andharness.ru.data.tools.contacts

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.undo.UndoRecorder

/**
 * Builds one [ToolHandler] per contacts tool: [contact_search], [contact_get],
 * [contact_create], [contact_update], [contact_delete], [contact_lookup_by_number],
 * [contact_groups_list], [contact_photo_get], [contact_star_set], [contact_batch_upsert],
 * [contact_merge], [contact_split], [contact_photo_set], [contact_photo_clear],
 * [contact_group_create], [contact_group_assign], [contact_birthdays],
 * [contact_export_vcf], [contact_import_vcf], [contact_group_delete], [contact_group_rename] — in
 * their original registration order.
 *
 * References:
 * - `ContactsContract.Contacts.CONTENT_URI`, `ContactsContract.Contacts._ID`,
 *   `ContactsContract.Contacts.DISPLAY_NAME`:
 *   https://developer.android.com/reference/android/provider/ContactsContract.Contacts
 * - `ContactsContract.CommonDataKinds.Phone.CONTENT_URI`, `NUMBER` (data1),
 *   `CONTACT_ID`, `DISPLAY_NAME`:
 *   https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Phone
 * - `ContactsContract.Contacts.CONTENT_FILTER_URI` (partial-name "type-to-filter" search; the
 *   filter is appended as a path segment) and `Uri.withAppendedPath`:
 *   https://developer.android.com/reference/android/provider/ContactsContract.Contacts#CONTENT_FILTER_URI
 * - `ContactsContract.CommonDataKinds.Email.CONTENT_URI`, `ADDRESS`, `CONTACT_ID`:
 *   https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Email
 * - `ContactsContract.Data` (`_ID`, `RAW_CONTACT_ID`, `MIMETYPE`, `CONTACT_ID`):
 *   https://developer.android.com/reference/android/provider/ContactsContract.Data
 * - contacts-provider guide (retrieve/search):
 *   https://developer.android.com/guide/topics/providers/contacts-provider
 * - `ContentResolver.query(uri, projection, selection, selectionArgs, sortOrder)`:
 *   https://developer.android.com/identity/providers/contacts-provider/retrieve-names
 */
class ContactsToolsHandler(
    private val context: Context,
    private val undoRecorder: UndoRecorder = UndoRecorder.NoOp,
) {

    fun handlers(): List<ToolHandler> {
        val io = ContactsProviderIo(context)
        val groupIo = ContactGroupIo(context)
        val aggregation = ContactAggregationApplier(context, io)
        return listOf(
            ContactSearchToolHandler(context, io),
            ContactGetToolHandler(io),
            ContactCreateToolHandler(context, undoRecorder),
            ContactUpdateToolHandler(context, io),
            ContactDeleteToolHandler(context, io),
            ContactLookupByNumberToolHandler(context),
            ContactGroupsListToolHandler(context),
            ContactPhotoGetToolHandler(context),
            ContactStarSetToolHandler(context),
            ContactBatchUpsertToolHandler(context, io),
            ContactMergeToolHandler(aggregation),
            ContactSplitToolHandler(aggregation),
            ContactPhotoSetToolHandler(context, io),
            ContactPhotoClearToolHandler(context, io),
            ContactGroupCreateToolHandler(context, groupIo),
            ContactGroupAssignToolHandler(context, io, groupIo),
            ContactBirthdaysToolHandler(io),
            ContactExportVcfToolHandler(context, io),
            ContactImportVcfToolHandler(context),
            ContactGroupDeleteToolHandler(context, groupIo),
            ContactGroupRenameToolHandler(context, groupIo),
        )
    }
}
