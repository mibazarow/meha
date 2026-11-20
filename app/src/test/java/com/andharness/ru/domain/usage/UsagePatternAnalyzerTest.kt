package com.andharness.ru.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * JVM tests for the predictive automation analyzer (update.md idea #16).
 *
 * They cover the conservative thresholds (min occurrences / min days / min confidence), the noise
 * filters, the evening/bedtime heuristic, label substitution, windowing and deterministic ordering.
 */
class UsagePatternAnalyzerTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** 2026-09-04 00:00 UTC is day offset 0; [now] is day 7 at 12:00. */
    private fun at(dayOffset: Int, hour: Int, minute: Int = 0, second: Int = 0): Long =
        LocalDate.of(2026, 9, 4)
            .plusDays(dayOffset.toLong())
            .atTime(hour, minute, second)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private val now: Long = at(7, 12)

    private fun app(
        pkg: String,
        dayOffset: Int,
        hour: Int,
        minute: Int = 0,
        durationMs: Long = 10 * 60_000L
    ): UsagePatternEvent {
        val start = at(dayOffset, hour, minute)
        return UsagePatternEvent(pkg, start, start + durationMs, UsageEventKind.APP_FOREGROUND)
    }

    private fun screenOff(dayOffset: Int, hour: Int, minute: Int = 0): UsagePatternEvent {
        val start = at(dayOffset, hour, minute)
        return UsagePatternEvent("", start, start, UsageEventKind.SCREEN_NON_INTERACTIVE)
    }

    @Test
    fun `app used on five of seven days yields one suggestion`() {
        val events = buildList {
            for (day in 0..4) add(app("com.social.chat", day, 20, 30))
            // Noise: a second app seen on the two remaining days only twice → below thresholds, but
            // it still counts as "observed days" for the confidence denominator.
            add(app("com.noise.app", 5, 9))
            add(app("com.noise.app", 6, 9))
        }

        val result = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 7),
            nowMillis = now,
            zoneId = zone,
            appLabel = { "Чат" }
        )

        assertEquals(1, result.size)
        val s = result.first()
        assertEquals(SuggestionKind.APP_USAGE, s.kind)
        assertEquals("app:com.social.chat:20", s.id)
        assertEquals("com.social.chat", s.packageName)
        assertEquals(20, s.hourOfDay)
        assertEquals(5, s.days)
        assertEquals(5, s.occurrences)
        assertEquals(7, s.observedDays)
        assertEquals(5.0 / 7.0, s.confidence, 0.001)
        assertEquals("time", s.triggerType)
        assertEquals("20:00", s.triggerConfig)
        assertEquals("notify", s.actionType)
        assertTrue(s.title.contains("Чат"))
        assertTrue(s.description.contains("Чат"))
        assertTrue(s.description.contains("5 из 7"))
    }

    @Test
    fun `below min days is not suggested`() {
        val events = buildList {
            repeat(2) { add(app("com.rare.app", 0, 19)) }
            repeat(2) { add(app("com.rare.app", 1, 19)) }
            for (day in 2..6) add(app("com.noise.app", day, 9))
        }

        val result = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 7, minDays = 3),
            nowMillis = now,
            zoneId = zone
        )

        assertTrue(result.none { it.packageName == "com.rare.app" })
    }

    @Test
    fun `confidence floor is enforced`() {
        val events = buildList {
            repeat(2) { add(app("com.seldom.app", 0, 20)) }
            add(app("com.seldom.app", 1, 20))
            add(app("com.seldom.app", 2, 20))
            for (day in 3..9) add(app("com.other.app", day, 9))
        }

        // Day 10 = "now" so the whole 10-day window (offsets 0..9) is in the past.
        val laterNow = at(10, 12)
        val strict = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 10, minConfidence = 0.5),
            nowMillis = laterNow,
            zoneId = zone
        )
        assertTrue(strict.none { it.packageName == "com.seldom.app" })

        val relaxed = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 10, minConfidence = 0.25),
            nowMillis = laterNow,
            zoneId = zone
        )
        val s = relaxed.firstOrNull { it.packageName == "com.seldom.app" }
        assertNotNull(s)
        assertEquals(3, s!!.days)
        assertEquals(0.3, s.confidence, 0.001)
    }

    @Test
    fun `short foreground blips are ignored as noise`() {
        val events = (0..4).map { day ->
            app("com.blip.app", day, 20, 30, durationMs = 10_000L)
        }

        val result = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 7, minOccurrences = 4),
            nowMillis = now,
            zoneId = zone
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `events with unknown duration are still counted`() {
        val events = (0..4).map { day ->
            val start = at(day, 20, 30)
            UsagePatternEvent("com.unpaired.app", start, start, UsageEventKind.APP_FOREGROUND)
        }

        val result = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 7),
            nowMillis = now,
            zoneId = zone
        )

        assertEquals(1, result.size)
        assertEquals(SuggestionKind.APP_USAGE, result.first().kind)
    }

    @Test
    fun `excluded packages are never suggested`() {
        val events = (0..4).map { day -> app("com.self.app", day, 20, 30) }

        val result = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 7, excludedPackages = setOf("com.self.app")),
            nowMillis = now,
            zoneId = zone
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `repeated evening screen off yields the bedtime suggestion`() {
        val events = (0..4).map { day -> screenOff(day, 23, 4) }

        val result = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 7),
            nowMillis = now,
            zoneId = zone
        )

        assertEquals(1, result.size)
        val s = result.first()
        assertEquals(SuggestionKind.QUIET_NIGHT, s.kind)
        assertEquals("night:23", s.id)
        assertNull(s.packageName)
        assertEquals(23, s.hourOfDay)
        assertEquals("23:00", s.triggerConfig)
        assertEquals("time", s.triggerType)
        assertEquals("notify", s.actionType)
        assertTrue(s.description.contains("Не беспокоить"))
        assertTrue(s.description.contains("не может сам переключить"))
    }

    @Test
    fun `no screen events means no bedtime suggestion`() {
        val events = (0..4).map { day -> app("com.social.chat", day, 20, 30) }

        val result = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 7),
            nowMillis = now,
            zoneId = zone
        )

        assertTrue(result.none { it.kind == SuggestionKind.QUIET_NIGHT })
    }

    @Test
    fun `daytime screen off is not a bedtime pattern`() {
        val events = (0..4).map { day -> screenOff(day, 14, 0) }

        val result = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 7),
            nowMillis = now,
            zoneId = zone
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `events outside the window are ignored`() {
        val events = (0..4).map { day -> app("com.old.app", day - 30, 20, 30) }

        val result = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 7),
            nowMillis = now,
            zoneId = zone
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty history yields an honest empty result`() {
        val result = UsagePatternAnalyzer.analyze(
            events = emptyList(),
            config = UsageAnalysisConfig(windowDays = 7),
            nowMillis = now,
            zoneId = zone
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `suggestions are ordered by confidence and capped by maxSuggestions`() {
        val events = buildList {
            for (day in 1..6) add(app("com.always.app", day, 20, 30))
            for (day in 1..4) add(app("com.sometimes.app", day, 12, 0))
            for (day in 1..3) add(app("com.rarely.app", day, 8, 0))
        }

        val result = UsagePatternAnalyzer.analyze(
            events = events,
            config = UsageAnalysisConfig(windowDays = 7, maxSuggestions = 2, minDays = 2),
            nowMillis = now,
            zoneId = zone
        )

        assertEquals(2, result.size)
        assertEquals("com.always.app", result[0].packageName)
        assertTrue(result[0].confidence >= result[1].confidence)
    }
}
