package com.andharness.ru.data.tools.calendar

import android.content.Context
import android.provider.CalendarContract.Events
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `calendar_events`: lists events whose start falls inside the [from, to] range.
 *
 * Reference: `CalendarContract.Events` —
 * https://developer.android.com/reference/android/provider/CalendarContract.Events
 */
internal class CalendarEventsToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "calendar_events"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val fromArg = arguments["from"] as? JsonPrimitive
            val toArg = arguments["to"] as? JsonPrimitive
            if (fromArg == null || toArg == null) {
                return@withContext ToolResult(false, "Укажите from и to.")
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
            val projection = arrayOf(
                Events._ID,
                Events.TITLE,
                Events.DTSTART,
                Events.DTEND,
            )
            val selection = "${Events.DTSTART} >= ? AND ${Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(from.toString(), to.toString())
            val sortOrder = Events.DTSTART
            val cursor = context.contentResolver.query(
                Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )
            val lines = ArrayList<String>()
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(Events._ID)
                val titleIdx = c.getColumnIndex(Events.TITLE)
                val startIdx = c.getColumnIndex(Events.DTSTART)
                val endIdx = c.getColumnIndex(Events.DTEND)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val title = c.getString(titleIdx)
                    val start = c.getLong(startIdx)
                    val end = c.getLong(endIdx)
                    lines.add("$title | $start | $end (id=$id)")
                }
            }
            if (lines.isEmpty()) {
                ToolResult(true, "События в диапазоне не найдены.")
            } else {
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
