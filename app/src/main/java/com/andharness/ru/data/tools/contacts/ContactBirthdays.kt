package com.andharness.ru.data.tools.contacts

import java.time.DateTimeException
import java.time.LocalDate
import java.time.Year
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Android-free date math for `contact_birthdays`.
 *
 * Birthdays live in `ContactsContract.CommonDataKinds.Event` rows with
 * `TYPE = Event.TYPE_BIRTHDAY` (3) and `START_DATE` (an alias of `Data.DATA1`, type TEXT).
 * `START_DATE` is documented as "the event start date as the user entered it"; in practice the
 * platform contacts app writes it as ISO `yyyy-MM-dd` (with year) or `--MM-dd` (without year).
 * This parser is intentionally lenient: it accepts either separator style and tolerates a trailing
 * time part, and it drops invalid rows instead of failing the whole listing.
 *
 * References:
 * - `ContactsContract.CommonDataKinds.Event` (CONTENT_ITEM_TYPE = "vnd.android.cursor.item/contact_event",
 *   START_DATE = data1, TYPE = data2, TYPE_BIRTHDAY = 3):
 *   https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Event
 * - `ContactsContract.Data` (generic DATA1..DATA15, meaning fixed by MIMETYPE):
 *   https://developer.android.com/reference/android/provider/ContactsContract.Data
 *
 * Covered by the JVM test `ContactBirthdaysTest`.
 */
object ContactBirthdays {

    /** A birthday row read from the provider. */
    data class Entry(val contactId: String, val name: String, val rawDate: String)

    /** A parsed birthday occurrence inside the requested window. */
    data class Upcoming(
        val contactId: String,
        val name: String,
        val originalDate: String,
        val month: Int,
        val day: Int,
        val hasYear: Boolean,
        val daysUntil: Int,
    )

    /** Parsed month/day of a birthday; [year] is null when the source carried no year. */
    data class Birthday(val month: Int, val day: Int, val year: Int?)

    /** Leap year used to validate a 29 Feb date that has no year of its own. */
    private const val LEAP_PROBE_YEAR = 2024

    /** Parses [raw] into [Birthday], or `null` when it is blank/unparseable. */
    fun parse(raw: String?): Birthday? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        // Drop a trailing time part ("1990-05-14T00:00:00" or "1990-05-14 00:00:00").
        val datePart = text.substringBefore('T').substringBefore(' ')
        if (datePart.startsWith("--")) {
            return parseMonthDay(datePart.removePrefix("--"), year = null)
        }
        val parts = datePart.split('-', '/', '.')
        return when (parts.size) {
            2 -> parseMonthDay(datePart, year = null)
            3 -> {
                val year = parts[0].toIntOrNull() ?: return null
                val month = parts[1].toIntOrNull() ?: return null
                val day = parts[2].toIntOrNull() ?: return null
                validated(month, day, year)
            }
            else -> null
        }
    }

    private fun parseMonthDay(text: String, year: Int?): Birthday? {
        val parts = text.split('-', '/', '.')
        if (parts.size != 2) return null
        val month = parts[0].toIntOrNull() ?: return null
        val day = parts[1].toIntOrNull() ?: return null
        return validated(month, day, year)
    }

    private fun validated(month: Int, day: Int, year: Int?): Birthday? {
        if (month !in 1..12 || day !in 1..31) return null
        return try {
            LocalDate.of(year ?: LEAP_PROBE_YEAR, month, day)
            Birthday(month, day, year)
        } catch (e: DateTimeException) {
            null
        }
    }

    /**
     * The calendar date a [month]/[day] birthday falls on in [year]. A 29 Feb birthday is observed
     * on 28 Feb in a non-leap year (the "last day of February" convention) so it is never skipped.
     */
    fun occurrenceIn(year: Int, month: Int, day: Int): LocalDate {
        if (month == 2 && day == 29 && !Year.isLeap(year.toLong())) {
            return LocalDate.of(year, 2, 28)
        }
        return LocalDate.of(year, month, day)
    }

    /**
     * Days from [today] to the next occurrence of [month]/[day]. `0` means "today"; values are
     * always in `0..365` and roll over to next year once this year's date has passed.
     */
    fun daysUntil(month: Int, day: Int, today: LocalDate): Int {
        val thisYear = occurrenceIn(today.year, month, day)
        if (!thisYear.isBefore(today)) {
            return ChronoUnit.DAYS.between(today, thisYear).toInt()
        }
        val nextYear = occurrenceIn(today.year + 1, month, day)
        return ChronoUnit.DAYS.between(today, nextYear).toInt()
    }

    /**
     * Upcoming birthdays inside `[0, windowDays]`, soonest first; ties are broken by name and then
     * contact id so the output is deterministic. Unparseable rows are dropped.
     */
    fun upcoming(entries: List<Entry>, today: LocalDate, windowDays: Int): List<Upcoming> {
        val window = windowDays.coerceAtLeast(0)
        return entries
            .mapNotNull { entry ->
                val parsed = parse(entry.rawDate) ?: return@mapNotNull null
                Upcoming(
                    contactId = entry.contactId,
                    name = entry.name,
                    originalDate = entry.rawDate,
                    month = parsed.month,
                    day = parsed.day,
                    hasYear = parsed.year != null,
                    daysUntil = daysUntil(parsed.month, parsed.day, today),
                )
            }
            .filter { it.daysUntil <= window }
            .sortedWith(compareBy({ it.daysUntil }, { it.name }, { it.contactId }))
    }

    /** `MM-DD` (zero-padded), locale-independent. */
    fun formatMonthDay(month: Int, day: Int): String =
        String.format(Locale.US, "%02d-%02d", month, day)
}
