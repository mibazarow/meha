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

/**
 * `contact_group_rename`: updates the title of one contact group (label) by id.
 *
 * The group is read first (a missing id is an honest error) and the new title is read back from the
 * provider before success is reported, so a refused write is never presented as a rename.
 *
 * Reference: `ContactsContract.Groups` (CONTENT_URI, _ID, TITLE) —
 * https://developer.android.com/reference/android/provider/ContactsContract.Groups
 */
internal class ContactGroupRenameToolHandler(
    private val context: Context,
    private val groupIo: ContactGroupIo,
) : ToolHandler {

    override val name = "contact_group_rename"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val groupId = (arguments["group_id"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()
                ?: return@withContext ToolResult(false, "Укажите числовой group_id.")
            val title = (arguments["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (title.isEmpty()) {
                return@withContext ToolResult(false, "Укажите title.")
            }
            val oldTitle = groupIo.groupTitle(groupId)
                ?: return@withContext ToolResult(false, "Группа $groupId не найдена.")

            val values = ContentValues().apply { put(ContactsContract.Groups.TITLE, title) }
            val updated = context.contentResolver.update(
                ContactsContract.Groups.CONTENT_URI,
                values,
                "${ContactsContract.Groups._ID} = ?",
                arrayOf(groupId.toString()),
            )
            val storeTitle = groupIo.groupTitle(groupId)
            when {
                storeTitle == title ->
                    ToolResult(true, "Группа $groupId переименована: «$oldTitle» → «$title».")
                updated == 0 ->
                    ToolResult(false, "Провайдер контактов не изменил группу $groupId (возможно, она только для чтения).")
                else ->
                    ToolResult(false, "Переименование не подтвердилось: прочитано «$storeTitle» вместо «$title».")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
