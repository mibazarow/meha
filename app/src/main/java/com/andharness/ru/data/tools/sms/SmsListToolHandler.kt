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
 * `sms_list`: lists the newest inbox messages, optionally filtered by a BODY substring.
 *
 * References:
 * - `Telephony.Sms.Inbox.CONTENT_URI`, `Telephony.Sms.ADDRESS`, `Telephony.Sms.BODY`,
 *   `Telephony.Sms.DATE`:
 *   https://developer.android.com/reference/android/provider/Telephony.Sms
 * - `ContentResolver.query(uri, projection, selection, selectionArgs, sortOrder)`:
 *   https://developer.android.com/reference/android/content/ContentResolver#query(android.net.Uri,%20java.lang.String[],%20java.lang.String,%20java.lang.String[],%20java.lang.String)
 */
internal class SmsListToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "sms_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = ((arguments["query"] as? JsonPrimitive)?.content ?: "").trim()
            val limit = parseLimit(arguments["limit"] as? JsonPrimitive)
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
            )
            val selection: String?
            val selectionArgs: Array<String>?
            if (query.isNotEmpty()) {
                selection = Telephony.Sms.BODY + " LIKE ?"
                selectionArgs = arrayOf("%$query%")
            } else {
                selection = null
                selectionArgs = null
            }
            val sortOrder = Telephony.Sms.DATE + " DESC"
            val resolver = context.contentResolver
            val cursor = resolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )
            val lines = ArrayList<String>()
            cursor?.use { c ->
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                while (c.moveToNext() && lines.size < limit) {
                    val address = c.getString(addrIdx)
                    val body = c.getString(bodyIdx)
                    lines.add("from: $address\n$body\n---")
                }
            }
            if (lines.isEmpty()) {
                ToolResult(true, "Смс не найдены.")
            } else {
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
