package com.andharness.ru.data.tools.calendar

import android.content.Context
import android.net.Uri
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders

/**
 * Read/write helpers for the Calendar Provider, shared by the calendar tools.
 *
 * References:
 * - Calendar Provider overview (Events / Calendars / Reminders tables, insert rules):
 *   https://developer.android.com/identity/providers/calendar-provider
 * - `CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL` (writable = OWNER/EDITOR/CONTRIBUTOR):
 *   https://developer.android.com/reference/android/provider/CalendarContract.Calendars
 * - `CalendarContract.Attendees`:
 *   https://developer.android.com/reference/android/provider/CalendarContract.Attendees
 * - `ContentResolver.query(...)`:
 *   https://developer.android.com/reference/android/content/ContentResolver
 */
internal class CalendarSupport(private val context: Context) {

    /** First writable calendar id (access level >= contributor), or -1 when none exists. */
    fun findWritableCalendarId(): Long {
        val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_ACCESS_LEVEL)
        val cursor = context.contentResolver.query(
            Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )
        var result = -1L
        cursor?.use { c ->
            val idIdx = c.getColumnIndex(Calendars._ID)
            val accessIdx = c.getColumnIndex(Calendars.CALENDAR_ACCESS_LEVEL)
            while (c.moveToNext()) {
                if (result < 0) {
                    result = c.getLong(idIdx)
                }
                val access = c.getInt(accessIdx)
                if (access >= Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    result = c.getLong(idIdx)
                    break
                }
            }
        }
        return result
    }

    fun eventHasAttendee(eventId: Long, email: String): Boolean {
        // Android 16's calendar provider rejects a `_ID` projection on the attendees URI
        // ("Invalid column _ID"), so verify the inserted row by projecting ATTENDEE_EMAIL and
        // checking for a match on (EVENT_ID, ATTENDEE_EMAIL) instead of counting `_ID`.
        // https://developer.android.com/reference/android/provider/CalendarContract.AttendeesColumns#ATTENDEE_EMAIL
        return context.contentResolver.query(
            Attendees.CONTENT_URI,
            arrayOf(Attendees.ATTENDEE_EMAIL),
            "${Attendees.EVENT_ID} = ? AND ${Attendees.ATTENDEE_EMAIL} = ?",
            arrayOf(eventId.toString(), email),
            null,
        )?.use { c -> c.moveToFirst() } ?: false
    }

    fun eventHasReminder(eventId: Long, minutes: Long): Boolean =
        queryCount(
            Reminders.CONTENT_URI,
            "${Reminders.EVENT_ID} = ? AND ${Reminders.MINUTES} = ?",
            arrayOf(eventId.toString(), minutes.toString()),
        ) > 0

    fun queryEventRrule(eventId: Long): String? {
        var rrule: String? = null
        context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events.RRULE),
            "${Events._ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(Events.RRULE)
                if (idx >= 0 && !c.isNull(idx)) rrule = c.getString(idx)
            }
        }
        return rrule
    }

    fun queryCount(uri: Uri, selection: String, args: Array<String>): Int {
        var count = 0
        context.contentResolver.query(uri, arrayOf("_ID"), selection, args, null)?.use { c ->
            count = c.count
        }
        return count
    }

    /** Accepts an RFC5545 duration ("PT1H") as-is, or a plain number of seconds. */
    fun rfc2445Duration(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("P", ignoreCase = true)) return trimmed.uppercase()
        val seconds = trimmed.toLongOrNull()
            ?: throw IllegalArgumentException("Некорректная duration: «$raw» (ожидается PT1H или число секунд).")
        return "PT${seconds}S"
    }
}
