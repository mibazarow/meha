package com.andharness.ru.data.tools.sms

import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.Telephony
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `sms_read_state`: marks every message of a thread read/unread.
 *
 * Marking SMS read/unread is a default-SMS-app capability: the platform only lets the current SMS
 * role holder write the `read` column. Report the real restriction instead of faking success.
 * https://developer.android.com/reference/android/provider/Telephony.Sms
 */
internal class SmsReadStateToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "sms_read_state"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val threadArg = arguments["thread_id"] as? JsonPrimitive
            if (threadArg == null || threadArg.content.isBlank()) {
                return@withContext ToolResult(false, "Укажите thread_id.")
            }
            val threadId = threadArg.content.trim()
            val readArg = arguments["read"] as? JsonPrimitive
            val read = readArg?.content?.toBooleanStrictOrNull() ?: true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null &&
                    roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_SMS)
                ) {
                    return@withContext ToolResult(
                        false,
                        "Приложение не является приложением SMS по умолчанию, поэтому платформа " +
                            "запрещает менять статус прочтения. Выдайте роль SMS (role_request) и повторите.",
                    )
                }
            }

            val values = ContentValues().apply {
                put(Telephony.Sms.READ, if (read) 1 else 0)
            }
            val updated = context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                Telephony.Sms.THREAD_ID + " = ?",
                arrayOf(threadId),
            )
            if (updated <= 0) {
                ToolResult(false, "SMS в диалоге $threadId не найдены (ничего не изменено).")
            } else {
                val state = if (read) "прочитанным" else "непрочитанным"
                ToolResult(true, "Диалог $threadId помечен $state (изменено сообщений: $updated).")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Платформа запретила изменение SMS: ${e.message}")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
