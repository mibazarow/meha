package com.andharness.ru.data.tools.calendar

import android.content.Context
import android.provider.CalendarContract.Instances
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `calendar_instances`: expands recurring events into individual occurrences inside [from, to].
 *
 * Reference: `CalendarContract.Instances.query(...)` —
 * https://developer.android.com/reference/android/provider/CalendarContract
 */
internal class CalendarInstancesToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "calendar_instances"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val fromArg = arguments["from"] as? JsonPrimitive
            val toArg = arguments["to"] as? JsonPrimitive
            if (fromArg == null || toArg == null) {
                return@withContext ToolResult(false, "Укажите from и to (ISO-дата или epoch millis).")
            }
            val from = try {
                CalendarTimeParser.parse(fromArg.content)
            } catch (e: Exception) {
                return@withContext ToolResult(false, "Некорректный from: «${fromArg.content}».")
            }
            val to = try {
                CalendarTimeParser.parse(toArg.content, endOfDay = true)
            } catch (e: Exception) {
                return@withContext ToolResult(false, "Некорректный to: «${toArg.content}».")
            }
            if (to < from) {
                return@withContext ToolResult(false, "to раньше from.")
            }

            // CalendarContract.Instances.query(cr, projection, begin, end) expands recurring
            // events into their individual occurrences inside [begin, end]. References:
            // CalendarContract.Instances —
            // https://developer.android.com/reference/android/provider/CalendarContract
            // https://developer.android.com/identity/providers/calendar-provider
            val projection = arrayOf(
                Instances.EVENT_ID,
                Instances.TITLE,
                Instances.BEGIN,
                Instances.END,
                Instances.CALENDAR_ID,
            )
            val cursor = Instances.query(context.contentResolver, projection, from, to)
            val lines = ArrayList<String>()
            cursor?.use { c ->
                val eventIdx = c.getColumnIndex(Instances.EVENT_ID)
                val titleIdx = c.getColumnIndex(Instances.TITLE)
                val beginIdx = c.getColumnIndex(Instances.BEGIN)
                val endIdx = c.getColumnIndex(Instances.END)
                val calIdx = c.getColumnIndex(Instances.CALENDAR_ID)
                while (c.moveToNext()) {
                    lines.add(
                        "${c.getString(titleIdx)} | " +
                            "начало=${c.getLong(beginIdx)} | конец=${c.getLong(endIdx)} | " +
                            "календарь=${c.getString(calIdx)} (eventId=${c.getString(eventIdx)})",
                    )
                }
            }
            if (lines.isEmpty()) {
                ToolResult(true, "События за период не найдены.")
            } else {
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на чтение календаря (нужен READ_CALENDAR).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
