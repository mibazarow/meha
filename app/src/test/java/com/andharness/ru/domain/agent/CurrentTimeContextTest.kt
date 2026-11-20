package com.andharness.ru.domain.agent

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bug #1: the injected prompt block must reflect the given instant, zone and locale. */
class CurrentTimeContextTest {

    /** 2026-09-17T17:45:00Z = 20:45 MSK, a Thursday. */
    private val now = Instant.parse("2026-09-17T17:45:00Z").toEpochMilli()

    @Test
    fun rendersDateClockWeekdayAndZoneInTheUserLocale() {
        val block = CurrentTimeContext.render(now, ZoneId.of("Europe/Moscow"), Locale.forLanguageTag("ru"))
        assertTrue(block.contains("17.09.2026"))
        assertTrue(block.contains("20:45"))
        assertTrue("Russian locale must name the weekday in Russian", block.contains("четверг"))
        assertTrue(block.contains("Europe/Moscow"))
        assertTrue("the offset must be stated", block.contains("UTC+03:00"))
        assertTrue("the authoritative epoch millis must be present", block.contains(now.toString()))
    }

    @Test
    fun sameInstantIsRenderedInTheGivenZone() {
        val block = CurrentTimeContext.render(now, ZoneId.of("UTC"), Locale.ENGLISH)
        assertTrue(block.contains("17:45"))
        assertTrue(block.contains("Thursday"))
        assertFalse("the Moscow wall-clock must not leak into a UTC rendering", block.contains("20:45"))
    }

    /**
     * Prefix-cache contract: within one minute the block must be byte-identical, so a second turn
     * does not invalidate the provider prefix for a value the model never sees at second precision.
     */
    @Test
    fun blockIsByteIdenticalWithinTheSameMinute() {
        val zone = ZoneId.of("Europe/Moscow")
        val first = CurrentTimeContext.render(now, zone, Locale.forLanguageTag("ru"))
        val plus59s = CurrentTimeContext.render(now + 59_999L, zone, Locale.forLanguageTag("ru"))
        assertEquals(first, plus59s)
    }

    @Test
    fun blockChangesOnTheNextMinute() {
        val zone = ZoneId.of("Europe/Moscow")
        val first = CurrentTimeContext.render(now, zone, Locale.forLanguageTag("ru"))
        val nextMinute = CurrentTimeContext.render(now + 60_000L, zone, Locale.forLanguageTag("ru"))
        assertNotEquals(first, nextMinute)
        assertTrue(nextMinute.contains("20:46"))
    }
}
