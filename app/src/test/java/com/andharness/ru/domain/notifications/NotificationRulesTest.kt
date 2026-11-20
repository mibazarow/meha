package com.andharness.ru.domain.notifications

import com.andharness.ru.domain.notify.CapturedNotification
import com.andharness.ru.domain.notify.NotificationDigest
import com.andharness.ru.domain.notify.TriageCategory
import com.andharness.ru.domain.notify.TriageImportance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class NotificationRulesTest {

    private val telegram = "org.telegram.messenger"

    private fun rules(
        apps: Map<String, AppRule> = emptyMap(),
        quiet: QuietHours? = null,
        allowImportant: Boolean = true,
        mode: SummaryMode = SummaryMode.ALL,
    ) = NotificationRules(apps, quiet, allowImportant, mode)

    // ---- classify -----------------------------------------------------------

    @Test
    fun defaultRulesClassifyEverythingAsNormal() {
        assertEquals(RuleImportance.NORMAL, NotificationRules.classify(telegram, NotificationRules()))
        assertEquals(RuleImportance.NORMAL, NotificationRules.classify(null, NotificationRules()))
        assertEquals(RuleImportance.NORMAL, NotificationRules.classify("  ", NotificationRules()))
    }

    @Test
    fun perAppRulesAreHonoured() {
        val r = rules(
            apps = mapOf(
                "ru.bank" to AppRule("ru.bank", RuleImportance.MUTE),
                telegram to AppRule(telegram, RuleImportance.IMPORTANT),
            )
        )
        assertEquals(RuleImportance.MUTE, NotificationRules.classify("ru.bank", r))
        assertEquals(RuleImportance.IMPORTANT, NotificationRules.classify(telegram, r))
        assertEquals(RuleImportance.NORMAL, NotificationRules.classify("com.unknown", r))
    }

    @Test
    fun classifyIsCaseInsensitiveAndTrims() {
        val r = rules(apps = mapOf("ORG.Telegram.Messenger" to AppRule("ORG.Telegram.Messenger", RuleImportance.MUTE)))
        assertEquals(RuleImportance.MUTE, NotificationRules.classify(telegram, r))
        assertEquals(RuleImportance.MUTE, NotificationRules.classify("  $telegram  ", r))
    }

    @Test
    fun importanceAndSummaryTokensParse() {
        assertEquals(RuleImportance.MUTE, NotificationRuleTokens.importance("MUTE"))
        assertEquals(RuleImportance.IMPORTANT, NotificationRuleTokens.importance("важное"))
        assertEquals(RuleImportance.NORMAL, NotificationRuleTokens.importance("normal"))
        assertNull(NotificationRuleTokens.importance("bogus"))
        assertNull(NotificationRuleTokens.importance(null))

        assertEquals(SummaryMode.IMPORTANT_ONLY, NotificationRuleTokens.summaryMode("important_only"))
        assertEquals(SummaryMode.OFF, NotificationRuleTokens.summaryMode("выкл"))
        assertEquals(SummaryMode.ALL, NotificationRuleTokens.summaryMode("all"))
        assertNull(NotificationRuleTokens.summaryMode("bogus"))
    }

    // ---- quiet hours --------------------------------------------------------

    @Test
    fun nullQuietHoursAreNeverQuiet() {
        assertFalse(NotificationRules.isQuiet(LocalTime.of(3, 0), null))
    }

    @Test
    fun daytimeWindowIsStartInclusiveEndExclusive() {
        val quiet = QuietHours.of(9, 0, 17, 0)
        assertFalse(NotificationRules.isQuiet(LocalTime.of(8, 59), quiet))
        assertTrue(NotificationRules.isQuiet(LocalTime.of(9, 0), quiet))
        assertTrue(NotificationRules.isQuiet(LocalTime.of(16, 59), quiet))
        assertFalse(NotificationRules.isQuiet(LocalTime.of(17, 0), quiet))
        assertFalse(NotificationRules.isQuiet(LocalTime.of(23, 30), quiet))
    }

    @Test
    fun windowWrapsPastMidnight() {
        val quiet = QuietHours.of(23, 0, 7, 0)
        assertTrue(NotificationRules.isQuiet(LocalTime.of(23, 0), quiet))
        assertTrue(NotificationRules.isQuiet(LocalTime.of(23, 59), quiet))
        assertTrue(NotificationRules.isQuiet(LocalTime.of(0, 0), quiet))
        assertTrue(NotificationRules.isQuiet(LocalTime.of(6, 59), quiet))
        assertFalse(NotificationRules.isQuiet(LocalTime.of(7, 0), quiet))
        assertFalse(NotificationRules.isQuiet(LocalTime.of(22, 59), quiet))
    }

    @Test
    fun emptyWindowIsNeverQuiet() {
        val quiet = QuietHours(600, 600)
        for (h in 0..23) {
            assertFalse(NotificationRules.isQuiet(LocalTime.of(h, 0), quiet))
        }
    }

    @Test
    fun epochMillisOverloadUsesTheZone() {
        val quiet = QuietHours.of(9, 0, 17, 0)
        val tenUtc = LocalDateTime.of(2024, 1, 1, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val twentyUtc = LocalDateTime.of(2024, 1, 1, 20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertTrue(NotificationRules.isQuiet(tenUtc, quiet, ZoneOffset.UTC))
        assertFalse(NotificationRules.isQuiet(twentyUtc, quiet, ZoneOffset.UTC))
        assertFalse(NotificationRules.isQuiet(tenUtc, null, ZoneOffset.UTC))
    }

    // ---- summary mode (digest) ---------------------------------------------

    private fun entry(app: String, importance: TriageImportance) = CapturedNotification(
        app = app,
        sender = null,
        summary = "summary $importance",
        importance = importance,
        category = TriageCategory.OTHER,
        reasons = emptyList(),
        at = 1_700_000_000_000L,
    )

    private val entries = listOf(
        entry("a", TriageImportance.HIGH),
        entry("b", TriageImportance.NORMAL),
        entry("c", TriageImportance.LOW),
    )

    @Test
    fun summaryModeAllShowsEverythingAndIsTheDefault() {
        val digest = NotificationDigest.build(entries)
        assertTrue(digest.contains("summary HIGH"))
        assertTrue(digest.contains("summary NORMAL"))
        assertTrue(digest.contains("summary LOW"))
        assertEquals(digest, NotificationDigest.build(entries, 30, SummaryMode.ALL))
    }

    @Test
    fun summaryModeImportantOnlyHidesNormalAndLow() {
        val digest = NotificationDigest.build(entries, 30, SummaryMode.IMPORTANT_ONLY)
        assertTrue(digest.contains("summary HIGH"))
        assertFalse(digest.contains("summary NORMAL"))
        assertFalse(digest.contains("summary LOW"))
        assertTrue(digest.contains("только важное"))
    }

    @Test
    fun summaryModeImportantOnlyWithNoImportantIsHonest() {
        val digest = NotificationDigest.build(listOf(entry("b", TriageImportance.NORMAL)), 30, SummaryMode.IMPORTANT_ONLY)
        assertTrue(digest.contains("важных нет"))
    }

    @Test
    fun summaryModeOffShowsNothing() {
        val digest = NotificationDigest.build(entries, 30, SummaryMode.OFF)
        assertTrue(digest.contains("отключена"))
        assertFalse(digest.contains("summary HIGH"))
    }

    // ---- codec / bounds -----------------------------------------------------

    @Test
    fun codecRoundTrips() {
        val original = rules(
            apps = mapOf("ru.bank" to AppRule("ru.bank", RuleImportance.MUTE, "спам")),
            quiet = QuietHours.of(23, 0, 7, 0),
            allowImportant = false,
            mode = SummaryMode.IMPORTANT_ONLY,
        )
        val decoded = NotificationRulesCodec.decode(NotificationRulesCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun corruptBlobDegradesToNoOpDefaults() {
        assertEquals(NotificationRules(), NotificationRulesCodec.decode("{not json"))
        assertEquals(NotificationRules(), NotificationRulesCodec.decode(null))
    }

    @Test
    fun codecDropsBlankPackagesAndClampsNote() {
        val long = "x".repeat(NotificationRulesCodec.MAX_NOTE_CHARS + 50)
        val decoded = NotificationRulesCodec.decode(
            NotificationRulesCodec.encode(
                rules(apps = mapOf("  " to AppRule("  ", RuleImportance.MUTE), "ok.app" to AppRule("ok.app", RuleImportance.NORMAL, long)))
            )
        )
        assertEquals(setOf("ok.app"), decoded.apps.keys)
        assertEquals(NotificationRulesCodec.MAX_NOTE_CHARS, decoded.apps.getValue("ok.app").note!!.length)
    }

    @Test
    fun codecBoundsTheAppMap() {
        val many = (1..NotificationRulesCodec.MAX_APPS + 50).associate { i ->
            "app.$i" to AppRule("app.$i", RuleImportance.NORMAL)
        }
        val bounded = NotificationRulesCodec.sanitize(rules(apps = many))
        assertEquals(NotificationRulesCodec.MAX_APPS, bounded.apps.size)
        // Newest entries are kept, oldest dropped.
        assertFalse(bounded.apps.containsKey("app.1"))
        assertTrue(bounded.apps.containsKey("app.${NotificationRulesCodec.MAX_APPS + 50}"))
    }
}
