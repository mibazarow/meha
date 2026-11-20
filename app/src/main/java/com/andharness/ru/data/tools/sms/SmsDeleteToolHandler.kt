package com.andharness.ru.data.tools.sms

import android.app.role.RoleManager
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.BaseColumns
import android.provider.Telephony
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `sms_delete`: deletes SMS matching a scope (`all` / `thread` / `number`).
 *
 * Platform fact: on Q+ only the holder of `RoleManager.ROLE_SMS` may call
 * `ContentResolver.delete(Telephony.Sms.CONTENT_URI, …)`; without the role the platform throws
 * `SecurityException`. This handler reports that real restriction instead of faking success. It also
 * refuses to delete unless `confirm=true`, because SMS deletion is irreversible (Undo cannot restore
 * SMS). It never crashes: every failure is returned as a [ToolResult].
 *
 * References:
 * - `Telephony.Sms` / `Telephony.Sms.CONTENT_URI`:
 *   https://developer.android.com/reference/android/provider/Telephony.Sms
 * - `ContentResolver.delete(uri, selection, selectionArgs)`:
 *   https://developer.android.com/reference/android/content/ContentResolver#delete(android.net.Uri,%20java.lang.String,%20java.lang.String[])
 * - `RoleManager.ROLE_SMS` / `isRoleHeld`:
 *   https://developer.android.com/reference/android/app/role/RoleManager#ROLE_SMS
 *   https://developer.android.com/reference/android/app/role/RoleManager#isRoleHeld(java.lang.String)
 * - Default SMS app requirements:
 *   https://developer.android.com/guide/topics/permissions/default-handlers#sms
 */
internal class SmsDeleteToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "sms_delete"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val confirm = (arguments["confirm"] as? JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() == true
            val scope = (arguments["scope"] as? JsonPrimitive)?.content
            val threadId = (arguments["thread_id"] as? JsonPrimitive)?.content
            val number = (arguments["number"] as? JsonPrimitive)?.content

            if (!confirm) {
                return@withContext ToolResult(
                    false,
                    "sms_delete отклонён: удаление не подтверждено (нет confirm=true). " +
                        "Удаление SMS необратимо — Undo его не восстановит.",
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null &&
                    roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_SMS)
                ) {
                    return@withContext ToolResult(
                        false,
                        "Удаление SMS не выполнено: Android разрешает удалять SMS только приложению SMS по " +
                            "умолчанию (роль ROLE_SMS). Сделайте Меху приложением SMS по умолчанию — вызовите " +
                            "role_request с ролью SMS (проверить: role_status/role_hold) — и повторите. " +
                            "Ничего не удалено.",
                    )
                }
            }

            val selection = SmsDeleteSelection.build(scope, threadId, number).getOrElse { error ->
                return@withContext ToolResult(false, error.message ?: "Некорректные параметры удаления.")
            }
            val (where, whereArgs) = selection

            val resolver = context.contentResolver
            val before = count(resolver, where, whereArgs)
            if (before == 0) {
                return@withContext ToolResult(
                    true,
                    "SMS не найдены по заданному условию (scope=${scope?.trim()}), ничего не удалено.",
                )
            }
            val deleted = try {
                resolver.delete(Telephony.Sms.CONTENT_URI, where, whereArgs)
            } catch (e: SecurityException) {
                return@withContext ToolResult(
                    false,
                    "Платформа запретила удаление SMS: ${e.message}. Ничего не удалено.",
                )
            }
            val after = count(resolver, where, whereArgs)
            val actual = before - after
            val detail = if (actual != deleted) {
                " Система сообщила об удалении $deleted, но счётчик изменился на $actual (было $before, стало $after)."
            } else {
                " (было $before, стало $after)."
            }
            ToolResult(deleted > 0, "Удалено SMS: $deleted.$detail")
        } catch (e: SecurityException) {
            ToolResult(false, "Платформа запретила удаление SMS: ${e.message}. Ничего не удалено.")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка удаления SMS: ${e.message}")
        }
    }

    /** Rows matching [selection]/[selectionArgs]; count without copying columns. */
    private fun count(
        resolver: ContentResolver,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = resolver.query(
        Telephony.Sms.CONTENT_URI,
        arrayOf(BaseColumns._ID),
        selection,
        selectionArgs,
        null,
    )?.use { it.count } ?: 0
}
