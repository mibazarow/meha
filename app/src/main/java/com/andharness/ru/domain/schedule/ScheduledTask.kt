package com.andharness.ru.domain.schedule

/**
 * A scheduled agent task ("cron"). [daysMask] bit 0 = Monday … bit 6 = Sunday.
 *
 * [atMs], when non-null, turns the task into a one-shot reminder at that epoch-millis instant
 * (see [isOneShot]); the recurring [hour]/[minute]/[daysMask] fields are then unused. They are
 * still populated (derived from [atMs] at creation time) only because the DB columns are NOT NULL.
 *
 * Reference: https://kotlinlang.org/docs/data-classes.html
 */
data class ScheduledTask(
    val id: Long,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    val daysMask: Int,
    val enabled: Boolean,
    val lastRunAt: Long,
    /** Epoch millis the task row was created; mirrored from the DB `createdAt` column. */
    val createdAt: Long = System.currentTimeMillis(),
    /** Chat the schedule was created from (SPEC §20); notifications for it deep-link there. */
    val chatId: Long? = null,
    /** Epoch millis of a one-shot reminder; null = ordinary recurring schedule. */
    val atMs: Long? = null
) {
    val timeText: String get() = "%02d:%02d".format(hour, minute)
    val daysText: String get() = daysMaskToText(daysMask)

    /** True when this task fires once at [atMs] instead of recurring on a weekday mask. */
    val isOneShot: Boolean get() = atMs != null

    companion object {
        const val EVERY_DAY = 0x7F          // Mon..Sun
        const val WEEKDAYS = 0x1F           // Mon..Fri
        const val WEEKENDS = 0x60           // Sat,Sun

        private val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

        fun daysMaskToText(mask: Int): String {
            val m = mask and EVERY_DAY
            return when (m) {
                EVERY_DAY -> "ежедневно"
                WEEKDAYS -> "по будням"
                WEEKENDS -> "по выходным"
                0 -> "нет дней"
                else -> (0..6).filter { (m shr it) and 1 == 1 }.joinToString(", ") { DAY_NAMES[it] }
            }
        }

        /**
         * Parses a human day spec into a mask. Accepts "daily"/"каждый день", "weekdays"/"будни",
         * "weekends"/"выходные", or a comma/space list of names ("mon,wed,fri", "пн ср пт").
         * Returns null when nothing matches.
         */
        fun parseDays(spec: String?): Int? {
            val s = spec?.trim()?.lowercase().orEmpty()
            if (s.isEmpty() || s == "daily" || s == "everyday" || s == "каждый день" || s == "ежедневно") {
                return EVERY_DAY
            }
            when (s) {
                "weekdays", "будни", "по будням", "будние" -> return WEEKDAYS
                "weekends", "выходные", "по выходным" -> return WEEKENDS
            }
            val tokens = s.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            var mask = 0
            for (t in tokens) {
                val day = dayIndex(t) ?: return null
                mask = mask or (1 shl day)
            }
            return if (mask == 0) null else mask
        }

        private fun dayIndex(token: String): Int? = when (token) {
            "mon", "monday", "пн", "понедельник", "1" -> 0
            "tue", "tues", "tuesday", "вт", "вторник", "2" -> 1
            "wed", "wednesday", "ср", "среда", "3" -> 2
            "thu", "thur", "thurs", "thursday", "чт", "четверг", "4" -> 3
            "fri", "friday", "пт", "пятница", "5" -> 4
            "sat", "saturday", "сб", "суббота", "6" -> 5
            "sun", "sunday", "вс", "воскресенье", "7", "0" -> 6
            else -> null
        }
    }
}
