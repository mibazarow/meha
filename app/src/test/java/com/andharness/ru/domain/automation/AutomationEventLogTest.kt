package com.andharness.ru.domain.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Pure checks of the `events_recent` line format, using a fixed zone so the
 * `18.09 22:14 · battery_low · Батарея разряжена` wording is deterministic.
 */
class AutomationEventLogTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** 2026-09-18 22:14:00 UTC. */
    private val at: Long = 1_789_769_640_000L

    private fun record(
        eventType: String = AutomationSpec.TRIGGER_BATTERY_LOW,
        value: String? = null,
        payload: String? = null,
        ruleName: String? = "Батарея разряжена"
    ) = AutomationEventRecord(
        eventType = eventType,
        value = value,
        payload = payload,
        ruleId = 3,
        ruleName = ruleName,
        createdAt = at
    )

    @Test
    fun lineMatchesTheAgreedShape() {
        assertEquals(
            "18.09 22:14 · battery_low · Батарея разряжена",
            AutomationEventLog.line(record(), zone)
        )
    }

    @Test
    fun observedValueIsAppendedWhenPresent() {
        assertEquals(
            "18.09 22:14 · battery_low · Батарея разряжена · com.example.app",
            AutomationEventLog.line(record(value = "com.example.app"), zone)
        )
    }

    @Test
    fun payloadIsUsedAsTheObservedFallbackOnlyWhenValueIsBlank() {
        assertEquals(
            "18.09 22:14 · notification · Правило · текст уведомления",
            AutomationEventLog.line(
                record(
                    eventType = AutomationSpec.TRIGGER_NOTIFICATION,
                    value = "  ",
                    payload = "текст уведомления",
                    ruleName = "Правило"
                ),
                zone
            )
        )
    }

    @Test
    fun blankNameAndMissingRuleDegradeWithoutInventingData() {
        val line = AutomationEventLog.line(
            AutomationEventRecord(eventType = "boot", ruleId = null, ruleName = null, createdAt = at),
            zone
        )
        assertEquals("18.09 22:14 · boot", line)
    }

    @Test
    fun formatJoinsNewestFirstRowsOnePerLine() {
        val text = AutomationEventLog.format(
            listOf(
                record(ruleName = "Первое"),
                record(eventType = AutomationSpec.TRIGGER_SCREEN_OFF, ruleName = "Второе")
            ),
            zone
        )
        assertEquals(
            "18.09 22:14 · battery_low · Первое\n18.09 22:14 · screen_off · Второе",
            text
        )
        assertTrue(AutomationEventLog.format(emptyList(), zone).isEmpty())
    }
}
