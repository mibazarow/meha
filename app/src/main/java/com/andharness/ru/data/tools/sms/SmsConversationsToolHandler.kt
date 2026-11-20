package com.andharness.ru.data.tools.sms

import android.content.Context
import android.provider.Telephony
import com.andharness.ru.data.tools.parseLimit
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `sms_conversations`: aggregates SMS rows per THREAD_ID in code and reports the newest
 * conversations.
 *
 * Do NOT query `content://sms/conversations`: the Conversations view only guarantees SNIPPET
 * ("snippet") and MESSAGE_COUNT ("msg_count"), and real devices throw `Invalid column date` when
 * `date` is requested on it. Instead read individual SMS rows from `content://sms` newest-first and
 * aggregate per THREAD_ID.
 * https://developer.android.com/reference/android/provider/Telephony.Sms
 * https://developer.android.com/reference/android/provider/Telephony.Sms.Conversations
 */
internal class SmsConversationsToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "sms_conversations"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val limit = parseLimit(arguments["limit"] as? JsonPrimitive)
            val projection = arrayOf(
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.DATE,
                Telephony.Sms.BODY,
            )
            val rows = ArrayList<SmsConversationRow>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                Telephony.Sms.DATE + " DESC",
            )?.use { c ->
                val threadIdx = c.getColumnIndex(Telephony.Sms.THREAD_ID)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                while (c.moveToNext()) {
                    rows.add(
                        SmsConversationRow(
                            threadId = c.getLong(threadIdx),
                            date = c.getLong(dateIdx),
                            body = c.getString(bodyIdx) ?: "",
                        ),
                    )
                }
            }
            val lines = aggregateSmsConversations(rows, limit)
            if (lines.isEmpty()) {
                ToolResult(true, "Диалоги SMS не найдены.")
            } else {
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на чтение SMS (нужен READ_SMS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
