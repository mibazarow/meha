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

/** `contact_group_create`: creates a contact group in a discovered or explicit account. */
internal class ContactGroupCreateToolHandler(
    private val context: Context,
    private val groupIo: ContactGroupIo,
) : ToolHandler {
    override val name = "contact_group_create"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val title = ((arguments["title"] as? JsonPrimitive)?.content ?: "").trim()
            if (title.isEmpty()) {
                return@withContext ToolResult(false, "Укажите title.")
            }
            // A group belongs to exactly one account, identified by ACCOUNT_TYPE +
            // ACCOUNT_NAME (from SyncColumns). A half-specified account is ambiguous, so the
            // pure resolver rejects it; with neither given it picks a discovered account or
            // falls back to a purely local group. Reference:
            // https://developer.android.com/reference/android/provider/ContactsContract.Groups
            val fallback = groupIo.defaultGroupAccount()
            val account = when (val resolution = ContactGroups.resolveGroupAccount(
                requestedType = (arguments["account_type"] as? JsonPrimitive)?.content,
                requestedName = (arguments["account_name"] as? JsonPrimitive)?.content,
                fallbackType = fallback?.type,
                fallbackName = fallback?.name,
            )) {
                is ContactGroups.AccountResolution.Invalid ->
                    return@withContext ToolResult(false, resolution.message)
                is ContactGroups.AccountResolution.Resolved -> resolution.account
            }
            // GROUP_VISIBLE=1 makes the group visible in the contacts UI.
            val values = ContentValues().apply {
                put(ContactsContract.Groups.TITLE, title)
                put(ContactsContract.Groups.GROUP_VISIBLE, 1)
                if (account.type != null && account.name != null) {
                    put(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
                    put(ContactsContract.Groups.ACCOUNT_NAME, account.name)
                }
            }
            val uri = context.contentResolver.insert(ContactsContract.Groups.CONTENT_URI, values)
                ?: return@withContext ToolResult(false, "Провайдер контактов не создал группу.")
            val groupId = uri.lastPathSegment?.toLongOrNull()
                ?: return@withContext ToolResult(false, "Группа создана, но её id не получен ($uri).")
            // Honest read-back: confirm the stored title is readable again.
            val storedTitle = groupIo.groupTitle(groupId)
            if (storedTitle == null) {
                ToolResult(false, "Группа $groupId создана, но не читается обратно.")
            } else {
                val accountText = if (account.name != null) {
                    "${account.name} (${account.type})"
                } else {
                    "локальный"
                }
                ToolResult(true, "Группа создана: id=$groupId, «$storedTitle», аккаунт: $accountText.")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
