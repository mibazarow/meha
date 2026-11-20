package com.andharness.ru.data.tools.contacts

import android.content.Context
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `contact_group_delete`: deletes one contact group (label) by id.
 *
 * The group is read first (so a missing id is an honest error, not a silent no-op) and the actual
 * row count returned by `ContentResolver.delete` is reported. Deletion is restricted by the
 * contacts provider to groups the caller's account may modify; when the provider refuses, the
 * failure is surfaced verbatim.
 *
 * Reference: `ContactsContract.Groups` (CONTENT_URI, _ID, TITLE) —
 * https://developer.android.com/reference/android/provider/ContactsContract.Groups
 */
internal class ContactGroupDeleteToolHandler(
    private val context: Context,
    private val groupIo: ContactGroupIo,
) : ToolHandler {

    override val name = "contact_group_delete"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val groupId = (arguments["group_id"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()
                ?: return@withContext ToolResult(false, "Укажите числовой group_id.")
            val title = groupIo.groupTitle(groupId)
                ?: return@withContext ToolResult(false, "Группа $groupId не найдена.")
            val deleted = context.contentResolver.delete(
                ContactsContract.Groups.CONTENT_URI,
                "${ContactsContract.Groups._ID} = ?",
                arrayOf(groupId.toString()),
            )
            if (deleted > 0 && groupIo.groupTitle(groupId) == null) {
                ToolResult(true, "Группа «$title» (id=$groupId) удалена.")
            } else if (deleted == 0) {
                ToolResult(false, "Провайдер контактов не удалил группу $groupId (возможно, она только для чтения).")
            } else {
                ToolResult(false, "Удаление группы $groupId не подтвердилось: она всё ещё читается.")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
