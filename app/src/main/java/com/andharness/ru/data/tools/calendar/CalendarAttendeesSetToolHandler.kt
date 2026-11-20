package com.andharness.ru.data.tools.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Attendees
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `calendar_attendees_set`: adds one attendee to an event and verifies the row was written.
 *
 * Reference: `CalendarContract.Attendees` —
 * https://developer.android.com/reference/android/provider/CalendarContract.Attendees
 */
internal class CalendarAttendeesSetToolHandler(
    private val context: Context,
    private val support: CalendarSupport,
) : ToolHandler {

    override val name = "calendar_attendees_set"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val eventId = (arguments["event_id"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()
                ?: return@withContext ToolResult(false, "Укажите числовой event_id.")
            val email = (arguments["email"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (email.isEmpty()) {
                return@withContext ToolResult(false, "Укажите email участника.")
            }
            val name = (arguments["name"] as? JsonPrimitive)?.content?.trim()
                ?.takeIf { it.isNotEmpty() }

            // Attendees rows require EVENT_ID and ATTENDEE_EMAIL; the other columns carry
            // sane defaults so the provider accepts the row.
            // https://developer.android.com/reference/android/provider/CalendarContract.Attendees
            val values = ContentValues().apply {
                put(Attendees.EVENT_ID, eventId)
                put(Attendees.ATTENDEE_EMAIL, email)
                if (name != null) put(Attendees.ATTENDEE_NAME, name)
                put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
                put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
                put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_NONE)
            }
            context.contentResolver.insert(Attendees.CONTENT_URI, values)
                ?: return@withContext ToolResult(false, "Не удалось добавить участника.")

            if (support.eventHasAttendee(eventId, email)) {
                ToolResult(true, "Участник $email добавлен к событию id=$eventId.")
            } else {
                ToolResult(false, "Участник не подтверждён после записи (event_id=$eventId).")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение календаря (нужен WRITE_CALENDAR).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
