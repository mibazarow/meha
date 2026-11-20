package com.andharness.ru.domain.message

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deterministic tests for the `at` parser of `schedule_message` (update.md §4.8): an `HH:MM` means the
 * next occurrence, an epoch is passed through (seconds are converted), and nonsense is rejected.
 */
class MessageScheduleTimeTest {

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-01-15T10:00:00Z").toEpochMilli()

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun clockTimeLaterTodayResolvesToday() {
        assertEquals(ms("2026-01-15T11:30:00Z"), MessageScheduleTime.parse("11:30", now, zone))
    }

    @Test
    fun clockTimeAlreadyPassedResolvesTomorrow() {
        assertEquals(ms("2026-01-16T09:30:00Z"), MessageScheduleTime.parse("09:30", now, zone))
    }

    @Test
    fun clockTimeExactlyNowResolvesTomorrow() {
        assertEquals(ms("2026-01-16T10:00:00Z"), MessageScheduleTime.parse("10:00", now, zone))
    }

    @Test
    fun epochMillisPassesThrough() {
        val target = ms("2026-02-01T08:00:00Z")
        assertEquals(target, MessageScheduleTime.parse(target.toString(), now, zone))
    }

    @Test
    fun epochSecondsAreConvertedToMillis() {
        val targetSeconds = ms("2026-02-01T08:00:00Z") / 1000L
        assertEquals(targetSeconds * 1000L, MessageScheduleTime.parse(targetSeconds.toString(), now, zone))
    }

    @Test
    fun invalidValuesAreRejected() {
        assertNull(MessageScheduleTime.parse(null, now, zone))
        assertNull(MessageScheduleTime.parse("", now, zone))
        assertNull(MessageScheduleTime.parse("tomorrow", now, zone))
        assertNull(MessageScheduleTime.parse("24:00", now, zone))
        assertNull(MessageScheduleTime.parse("10:60", now, zone))
        assertNull(MessageScheduleTime.parse("0", now, zone))
        assertNull(MessageScheduleTime.parse("-5", now, zone))
    }
}
