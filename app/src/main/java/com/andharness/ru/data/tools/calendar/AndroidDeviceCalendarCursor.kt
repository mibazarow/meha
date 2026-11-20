package com.andharness.ru.data.tools.calendar

import android.database.Cursor

/**
 * Adapts the platform `android.database.Cursor` returned by the Calendar Provider to the pure,
 * Android-free [DeviceCalendarCursor] shape the mapping logic uses. Column access is by name, so a
 * missing column fails fast with `IllegalArgumentException` instead of silently reading column 0.
 *
 * Reference: `android.database.Cursor` (`moveToNext`, `getLong`, `getInt`, `getString`) —
 * https://developer.android.com/reference/android/database/Cursor
 */
internal class AndroidDeviceCalendarCursor(private val cursor: Cursor) : DeviceCalendarCursor {

    override fun moveToNext(): Boolean = cursor.moveToNext()

    override fun getLong(column: String): Long = cursor.getLong(cursor.getColumnIndexOrThrow(column))

    override fun getInt(column: String): Int = cursor.getInt(cursor.getColumnIndexOrThrow(column))

    override fun getString(column: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(column))
}
