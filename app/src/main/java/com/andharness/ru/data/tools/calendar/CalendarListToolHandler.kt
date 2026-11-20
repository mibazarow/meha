package com.andharness.ru.data.tools.calendar

import android.content.Context
import android.provider.CalendarContract.Calendars
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `calendar_list`: lists the known calendars with their account, colour, visibility and access.
 *
 * Reference: `CalendarContract.Calendars` —
 * https://developer.android.com/reference/android/provider/CalendarContract.Calendars
 */
internal class CalendarListToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "calendar_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            // CalendarContract.Calendars columns: _ID, CALENDAR_DISPLAY_NAME, ACCOUNT_NAME,
            // ACCOUNT_TYPE, CALENDAR_COLOR, VISIBLE (1 = shown), CALENDAR_ACCESS_LEVEL.
            // Reference: CalendarContract.Calendars —
            // https://developer.android.com/reference/android/provider/CalendarContract
            val projection = arrayOf(
                Calendars._ID,
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.ACCOUNT_NAME,
                Calendars.ACCOUNT_TYPE,
                Calendars.CALENDAR_COLOR,
                Calendars.VISIBLE,
                Calendars.CALENDAR_ACCESS_LEVEL,
            )
            val lines = ArrayList<String>()
            context.contentResolver.query(
                Calendars.CONTENT_URI,
                projection,
                null,
                null,
                Calendars.CALENDAR_DISPLAY_NAME + " ASC",
            )?.use { c ->
                val idIdx = c.getColumnIndex(Calendars._ID)
                val nameIdx = c.getColumnIndex(Calendars.CALENDAR_DISPLAY_NAME)
                val accountIdx = c.getColumnIndex(Calendars.ACCOUNT_NAME)
                val typeIdx = c.getColumnIndex(Calendars.ACCOUNT_TYPE)
                val colorIdx = c.getColumnIndex(Calendars.CALENDAR_COLOR)
                val visibleIdx = c.getColumnIndex(Calendars.VISIBLE)
                val accessIdx = c.getColumnIndex(Calendars.CALENDAR_ACCESS_LEVEL)
                while (c.moveToNext()) {
                    val color = c.getInt(colorIdx) and 0xFFFFFF
                    lines.add(
                        "id=${c.getString(idIdx)} | ${c.getString(nameIdx)} | " +
                            "аккаунт=${c.getString(accountIdx)} (${c.getString(typeIdx)}) | " +
                            "цвет=#${color.toString(16).padStart(6, '0')} | " +
                            "видимый=${if (c.getInt(visibleIdx) == 1) "да" else "нет"} | " +
                            "доступ=${c.getInt(accessIdx)}",
                    )
                }
            }
            if (lines.isEmpty()) {
                ToolResult(true, "Календари не найдены.")
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
