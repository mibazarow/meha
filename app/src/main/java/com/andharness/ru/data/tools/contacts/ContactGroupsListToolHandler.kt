package com.andharness.ru.data.tools.contacts

import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `contact_groups_list`: lists contact groups (title + owning account), ordered by title. */
internal class ContactGroupsListToolHandler(
    private val context: Context,
) : ToolHandler {
    override val name = "contact_groups_list"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            // ContactsContract.Groups — contact groups/labels (title + owning account).
            // https://developer.android.com/reference/android/provider/ContactsContract.Groups
            val projection = arrayOf(
                ContactsContract.Groups._ID,
                ContactsContract.Groups.TITLE,
                ContactsContract.Groups.ACCOUNT_TYPE,
                ContactsContract.Groups.ACCOUNT_NAME,
            )
            val lines = ArrayList<String>()
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.Groups.TITLE + " ASC",
            )?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.Groups._ID)
                val titleIdx = c.getColumnIndex(ContactsContract.Groups.TITLE)
                val typeIdx = c.getColumnIndex(ContactsContract.Groups.ACCOUNT_TYPE)
                val nameIdx = c.getColumnIndex(ContactsContract.Groups.ACCOUNT_NAME)
                while (c.moveToNext()) {
                    val id = c.getString(idIdx)
                    val title = c.getString(titleIdx) ?: "(без названия)"
                    val account = c.getString(nameIdx) ?: c.getString(typeIdx) ?: "(локально)"
                    lines.add("$id | $title | аккаунт: $account")
                }
            }
            if (lines.isEmpty()) {
                ToolResult(true, "Группы контактов не найдены.")
            } else {
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на чтение контактов (нужен READ_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
