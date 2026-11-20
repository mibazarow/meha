package com.andharness.ru.data.tools.contacts

import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `contact_group_assign`: adds a contact to / removes it from a group, with read-back. */
internal class ContactGroupAssignToolHandler(
    private val context: Context,
    private val io: ContactsProviderIo,
    private val groupIo: ContactGroupIo,
) : ToolHandler {
    override val name = "contact_group_assign"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val remove = (arguments["remove"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            val contactIdArg = (arguments["contact_id"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val lookup = (arguments["contact_lookup"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val contactId = when {
                contactIdArg.isNotEmpty() -> contactIdArg
                lookup.isNotEmpty() -> io.contactIdByNumber(lookup)
                    ?: return@withContext ToolResult(false, "Контакт по номеру «$lookup» не найден.")
                else -> return@withContext ToolResult(false, "Укажите contact_id или contact_lookup.")
            }
            val displayName = io.contactDisplayName(contactId)?.takeIf { it.isNotBlank() } ?: "(без имени)"

            val groups = groupIo.allGroups()
            val groupId = when (val resolution = ContactGroups.resolveGroupId(
                groupId = (arguments["group_id"] as? JsonPrimitive)?.content,
                title = (arguments["group_title"] as? JsonPrimitive)?.content,
                groups = groups,
            )) {
                is ContactGroups.GroupResolution.Invalid ->
                    return@withContext ToolResult(false, resolution.message)
                is ContactGroups.GroupResolution.Resolved -> resolution.groupId
            }
            val group = groups.first { it.id == groupId }
            val groupTitle = group.title?.takeIf { it.isNotBlank() } ?: "(без названия)"

            // Decide from the groups the contact is actually a member of (read first), so an
            // idempotent add/remove is reported honestly instead of writing a duplicate row.
            when (ContactGroups.decideMembership(groupIo.groupMembershipIds(contactId), groupId, remove)) {
                ContactGroups.MembershipDecision.NotMember ->
                    return@withContext ToolResult(true, "Контакт $contactId уже не в группе «$groupTitle».")
                ContactGroups.MembershipDecision.AlreadyMember ->
                    return@withContext ToolResult(true, "Контакт $contactId уже в группе «$groupTitle».")
                else -> Unit
            }

            val rawContacts = groupIo.rawContactsWithAccounts(contactId)
            if (rawContacts.isEmpty()) {
                return@withContext ToolResult(false, "Контакт $contactId не найден (нет raw-контакта).")
            }
            val rawId = ContactGroups.pickRawContactForGroup(rawContacts, group.accountType, group.accountName)
                ?: return@withContext ToolResult(false, "Не найден raw-контакт для группы «$groupTitle».")

            // The documented write path: a Data row with MIMETYPE=group_membership whose
            // GROUP_ROW_ID (DATA1) is the target group id. Exactly one of GROUP_ROW_ID /
            // GROUP_SOURCE_ID must be set; this tool uses GROUP_ROW_ID.
            // https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.GroupMembership
            val selection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                "${ContactsContract.Data.MIMETYPE} = ? AND " +
                "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?"
            val selectionArgs = arrayOf(
                rawId.toString(),
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
                groupId.toString(),
            )

            if (remove) {
                val deleted = context.contentResolver.delete(
                    ContactsContract.Data.CONTENT_URI,
                    selection,
                    selectionArgs,
                )
                val stillMember = groupIo.groupMembershipIds(contactId).contains(groupId)
                if (!stillMember) {
                    ToolResult(true, "Контакт $contactId ($displayName) убран из группы «$groupTitle».")
                } else if (deleted > 0) {
                    ToolResult(false, "Удаление не подтвердилось: контакт всё ещё в группе «$groupTitle».")
                } else {
                    ToolResult(false, "Строка членства не найдена, удалить не удалось.")
                }
            } else {
                val values = ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    put(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
                    )
                    put(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
                }
                context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
                if (groupIo.groupMembershipIds(contactId).contains(groupId)) {
                    ToolResult(true, "Контакт $contactId ($displayName) добавлен в группу «$groupTitle».")
                } else {
                    ToolResult(false, "Добавление не подтвердилось: контакт не появился в группе «$groupTitle».")
                }
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
