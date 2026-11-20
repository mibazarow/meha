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
 * `sms_thread`: lists the messages of one thread (matched by address), oldest first, keeping the
 * newest `limit` rows.
 *
 * References:
 * - `Telephony.Sms.CONTENT_URI`, `Telephony.Sms.TYPE`, `Telephony.Sms.MESSAGE_TYPE_SENT`:
 *   https://developer.android.com/reference/android/provider/Telephony.Sms
 */
internal class SmsThreadToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "sms_thread"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val phone = (arguments["phone"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (phone.isEmpty()) return@withContext ToolResult(false, "Укажите phone.")
            val limit = parseLimit(arguments["limit"] as? JsonPrimitive)
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
            )
            val lines = ArrayList<String>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                Telephony.Sms.ADDRESS + " = ?",
                arrayOf(phone),
                Telephony.Sms.DATE + " ASC",
            )?.use { c ->
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                while (c.moveToNext()) {
                    val who = if (c.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_SENT) "я" else "он"
                    lines.add("$who ($phone): ${c.getString(bodyIdx)}")
                }
            }
            val tail = if (lines.size > limit) lines.takeLast(limit) else lines
            if (tail.isEmpty()) ToolResult(true, "Переписка с $phone не найдена.")
            else ToolResult(true, tail.joinToString("\n"))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
