package com.andharness.ru.domain.insights

import com.andharness.ru.domain.audit.ToolAuditEntry
import com.andharness.ru.domain.notify.CapturedNotification
import com.andharness.ru.domain.notify.TriageCategory
import com.andharness.ru.domain.notify.TriageImportance
import com.andharness.ru.domain.skill.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * JVM tests for the pure proactive-insight engine (checklist §1). Android-free by construction
 * (`java.time` only), so the statistical rules and the honest empty state are exercised here.
 */
class InsightAnalyzerTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** Fixed "now": the environment date, Monday 2026-09-21 12:00 UTC, for deterministic weekdays. */
    private val base: ZonedDateTime = ZonedDateTime.of(2026, 9, 21, 12, 0, 0, 0, zone)
    private val now: Long = base.toInstant().toEpochMilli()

    private fun daysAgo(days: Long, hour: Int = 12): Long =
        base.minusDays(days).withHour(hour).withMinute(0).withSecond(0).withNano(0)
            .toInstant().toEpochMilli()

    private fun audit(tool: String, at: Long): ToolAuditEntry =
        ToolAuditEntry(
            id = 0L,
            at = at,
            chatId = null,
            tool = tool,
            argsPreview = "",
            success = true,
            resultHash = "",
            durationMs = 0L,
            model = null,
            prevHash = "",
            hash = "",
        )

    private fun notification(app: String, at: Long): CapturedNotification =
        CapturedNotification(
            app = app,
            sender = null,
            summary = "…",
            importance = TriageImportance.NORMAL,
            category = TriageCategory.PERSONAL,
            reasons = emptyList(),
            at = at,
        )

    private fun skill(name: String, createdAt: Long, lastUsedAt: Long?): Skill =
        Skill(id = "id-$name", name = name, createdAt = createdAt, lastUsedAt = lastUsedAt)

    // ---- routine ----

    @Test
    fun routineIsReportedWhenWeekdayPatternIsJustified() {
        val entries = listOf(
            audit("files_list", daysAgo(7)),
            audit("files_list", daysAgo(14)),
            audit("files_list", daysAgo(21)),
            audit("files_list", daysAgo(28)),
            audit("files_list", daysAgo(3)),
            audit("files_list", daysAgo(10)),
            audit("calendar_list", daysAgo(2)),
            audit("sms_send", daysAgo(1)),
        )
        val insights = InsightAnalyzer.routineInsights(entries, now, zone)
        assertEquals(1, insights.size)
        val insight = insights.single()
        assertEquals(InsightKind.ROUTINE, insight.kind)
        assertTrue(insight.title.contains("files_list"))
        assertEquals(4, insight.evidenceCount)
        assertTrue("confidence must reflect the sample size", insight.confidence in 0.5..1.0)
    }

    @Test
    fun routineIsSkippedWhenSamplesAreTooFew() {
        val entries = listOf(
            audit("files_list", daysAgo(7)),
            audit("files_list", daysAgo(14)),
        )
        assertTrue(InsightAnalyzer.routineInsights(entries, now, zone).isEmpty())
    }

    @Test
    fun routineIsSkippedWhenTheWeekdayIsInconsistent() {
        // 8 invocations spread evenly over four weekdays: no dominant weekday, so no insight.
        val entries = buildList {
            for (week in 0L..1L) {
                for (day in 0L..3L) {
                    add(audit("tool_x", daysAgo(week * 7 + day)))
                }
            }
        }
        assertTrue(InsightAnalyzer.routineInsights(entries, now, zone).isEmpty())
    }

    @Test
    fun routineIsSkippedForEmptyJournal() {
        assertTrue(InsightAnalyzer.routineInsights(emptyList(), now, zone).isEmpty())
    }

    // ---- stale skills ----

    @Test
    fun staleSkillIsReported() {
        val skills = listOf(skill("Оплата", createdAt = daysAgo(120), lastUsedAt = daysAgo(60)))
        val insights = InsightAnalyzer.staleSkillInsights(skills, now, zone)
        assertEquals(1, insights.size)
        val insight = insights.single()
        assertEquals(InsightKind.STALE_SKILL, insight.kind)
        assertEquals(60, insight.evidenceCount)
        assertTrue(insight.title.contains("Оплата"))
        assertTrue(insight.detail.contains("60"))
    }

    @Test
    fun neverUsedSkillIsReportedAfterItIsOldEnough() {
        val skills = listOf(skill("Резервная копия", createdAt = daysAgo(45), lastUsedAt = null))
        val insights = InsightAnalyzer.staleSkillInsights(skills, now, zone)
        assertEquals(1, insights.size)
        assertEquals(InsightKind.STALE_SKILL, insights.single().kind)
    }

    @Test
    fun freshSkillIsNotReported() {
        val skills = listOf(skill("Новый", createdAt = daysAgo(5), lastUsedAt = null))
        assertTrue(InsightAnalyzer.staleSkillInsights(skills, now, zone).isEmpty())
    }

    @Test
    fun recentlyUsedSkillIsNotReported() {
        val skills = listOf(skill("Активный", createdAt = daysAgo(120), lastUsedAt = daysAgo(3)))
        assertTrue(InsightAnalyzer.staleSkillInsights(skills, now, zone).isEmpty())
    }

    // ---- notification load ----

    @Test
    fun notificationLoadAggregatesByApp() {
        val entries = buildList {
            repeat(5) { add(notification("com.whatsapp", daysAgo(0, hour = 10) - it * 60_000L)) }
            add(notification("com.telegram", daysAgo(1)))
        }
        val insights = InsightAnalyzer.notificationLoadInsights(entries, now)
        assertEquals(1, insights.size)
        val insight = insights.single()
        assertEquals(InsightKind.NOTIFICATION_LOAD, insight.kind)
        assertEquals(5, insight.evidenceCount)
        assertTrue(insight.title.contains("com.whatsapp"))
    }

    @Test
    fun notificationLoadIsSkippedBelowTheMinimumCount() {
        val entries = listOf(
            notification("com.whatsapp", daysAgo(0, hour = 10)),
            notification("com.whatsapp", daysAgo(0, hour = 11)),
        )
        assertTrue(InsightAnalyzer.notificationLoadInsights(entries, now).isEmpty())
    }

    @Test
    fun notificationsOutsideTheWindowAreIgnored() {
        val entries = listOf(notification("com.whatsapp", daysAgo(30, hour = 10)))
        assertTrue(InsightAnalyzer.notificationLoadInsights(entries, now).isEmpty())
    }

    @Test
    fun pluralRuPicksTheCorrectRussianForm() {
        val forms = Triple("уведомление", "уведомления", "уведомлений")
        assertEquals("уведомление", InsightAnalyzer.pluralRu(1, forms))
        assertEquals("уведомления", InsightAnalyzer.pluralRu(2, forms))
        assertEquals("уведомлений", InsightAnalyzer.pluralRu(5, forms))
        assertEquals("уведомлений", InsightAnalyzer.pluralRu(11, forms))
        assertEquals("уведомление", InsightAnalyzer.pluralRu(21, forms))
    }

    @Test
    fun pluralRuTreatsTheTeensAsMany() {
        val forms = Triple("уведомление", "уведомления", "уведомлений")
        assertEquals("уведомлений", InsightAnalyzer.pluralRu(11, forms))
        assertEquals("уведомлений", InsightAnalyzer.pluralRu(12, forms))
        assertEquals("уведомлений", InsightAnalyzer.pluralRu(13, forms))
        assertEquals("уведомлений", InsightAnalyzer.pluralRu(14, forms))
        assertEquals("уведомление", InsightAnalyzer.pluralRu(21, forms))
        assertEquals("уведомления", InsightAnalyzer.pluralRu(22, forms))
    }

    @Test
    fun notificationLoadDetailAgreesWithTheCount() {
        val three = InsightAnalyzer.notificationLoadInsights(
            buildList { repeat(3) { add(notification("com.whatsapp", daysAgo(0, hour = 10) - it * 60_000L)) } },
            now,
        ).single()
        assertTrue(three.detail.contains("3 уведомления от «com.whatsapp»"))

        val five = InsightAnalyzer.notificationLoadInsights(
            buildList { repeat(5) { add(notification("com.whatsapp", daysAgo(0, hour = 10) - it * 60_000L)) } },
            now,
        ).single()
        assertTrue(five.detail.contains("5 уведомлений от «com.whatsapp»"))
    }

    // ---- combined / honest empty state ----

    @Test
    fun emptyInputProducesEmptyListAndHonestMessage() {
        val insights = InsightAnalyzer.build(
            audit = emptyList(),
            skills = emptyList(),
            notifications = emptyList(),
            nowMillis = now,
            zone = zone,
        )
        assertTrue(insights.isEmpty())
        val text = InsightAnalyzer.render(insights)
        assertEquals(InsightAnalyzer.EMPTY_MESSAGE, text)
        assertTrue(text.contains("Инсайтов пока нет"))
        assertTrue("must not fabricate a habit", !text.contains("вторник"))
    }

    @Test
    fun buildCombinesAllKindsAndSortsByConfidence() {
        val insights = InsightAnalyzer.build(
            audit = listOf(
                audit("files_list", daysAgo(7)),
                audit("files_list", daysAgo(14)),
                audit("files_list", daysAgo(21)),
                audit("files_list", daysAgo(28)),
                audit("files_list", daysAgo(3)),
                audit("files_list", daysAgo(10)),
            ),
            skills = listOf(skill("Оплата", createdAt = daysAgo(120), lastUsedAt = daysAgo(60))),
            notifications = listOf(notification("com.whatsapp", daysAgo(0, hour = 10))),
            nowMillis = now,
            zone = zone,
        )
        assertEquals(
            listOf(InsightKind.ROUTINE, InsightKind.STALE_SKILL).toSet(),
            insights.map { it.kind }.toSet(),
        )
        val confidences = insights.map { it.confidence }
        assertEquals(confidences.sortedDescending(), confidences)
    }

    @Test
    fun kindFilterRestrictsTheSections() {
        val insights = InsightAnalyzer.build(
            audit = listOf(
                audit("files_list", daysAgo(7)),
                audit("files_list", daysAgo(14)),
                audit("files_list", daysAgo(21)),
                audit("files_list", daysAgo(28)),
                audit("files_list", daysAgo(3)),
                audit("files_list", daysAgo(10)),
            ),
            skills = listOf(skill("Оплата", createdAt = daysAgo(120), lastUsedAt = daysAgo(60))),
            nowMillis = now,
            zone = zone,
            kinds = setOf(InsightKind.STALE_SKILL),
        )
        assertEquals(1, insights.size)
        assertEquals(InsightKind.STALE_SKILL, insights.single().kind)
    }

    @Test
    fun limitCapsTheResult() {
        val insights = InsightAnalyzer.build(
            audit = listOf(
                audit("files_list", daysAgo(7)),
                audit("files_list", daysAgo(14)),
                audit("files_list", daysAgo(21)),
                audit("files_list", daysAgo(28)),
                audit("files_list", daysAgo(3)),
                audit("files_list", daysAgo(10)),
            ),
            skills = listOf(
                skill("Оплата", createdAt = daysAgo(120), lastUsedAt = daysAgo(60)),
                skill("Резервная копия", createdAt = daysAgo(90), lastUsedAt = null),
            ),
            nowMillis = now,
            zone = zone,
            limit = 1,
        )
        assertEquals(1, insights.size)
    }

    @Test
    fun kindTokenParsingIsHonest() {
        assertEquals(InsightKind.ROUTINE, InsightKind.fromToken("routine"))
        assertEquals(InsightKind.ROUTINE, InsightKind.fromToken(" Routine "))
        assertEquals(InsightKind.NOTIFICATION_LOAD, InsightKind.fromToken("notification-load"))
        assertEquals(InsightKind.STALE_SKILL, InsightKind.fromToken("stale_skill"))
        assertNull(InsightKind.fromToken("bogus"))
        assertNull(InsightKind.fromToken(null))
    }

    @Test
    fun renderListsTitlesAndConfidence() {
        val insights = InsightAnalyzer.notificationLoadInsights(
            buildList {
                repeat(8) { add(notification("com.whatsapp", daysAgo(0, hour = 10) - it * 60_000L)) }
            },
            now,
        )
        val text = InsightAnalyzer.render(insights)
        assertTrue(text.contains("com.whatsapp"))
        assertTrue(text.contains("уверенность 100%"))
    }
}
