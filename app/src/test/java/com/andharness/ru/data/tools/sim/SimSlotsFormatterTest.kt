package com.andharness.ru.data.tools.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SimSlotsFormatter] — the pure, Android-free row mapping of `sim_slots_list`.
 * No Android runtime: [SimSlotInfo] rows are built by hand exactly as the handler builds them from
 * `SubscriptionInfo`.
 */
class SimSlotsFormatterTest {

    @Test
    fun mapsEveryFieldIntoOneRow() {
        val output = SimSlotsFormatter.format(
            listOf(
                SimSlotInfo(
                    subscriptionId = 5,
                    slotIndex = 0,
                    carrierName = "МегаФон",
                    displayName = "Личная",
                    embedded = false,
                    isDefaultVoice = true,
                    isDefaultSms = true,
                    isDefaultData = true,
                ),
            )
        )
        assertTrue(output.contains("SIM-подписок: 1"))
        assertTrue(output.contains("slot=0"))
        assertTrue(output.contains("id=5"))
        assertTrue(output.contains("физическая"))
        assertTrue(output.contains("МегаФон"))
        assertTrue(output.contains("имя: Личная"))
        assertTrue(output.contains("по умолчанию: звонки/SMS/данные"))
    }

    @Test
    fun esimRowIsMarkedEmbedded() {
        val output = SimSlotsFormatter.format(
            listOf(
                SimSlotInfo(
                    subscriptionId = 9,
                    slotIndex = 1,
                    carrierName = "eSIM оператор",
                    displayName = "Рабочая",
                    embedded = true,
                    isDefaultVoice = false,
                    isDefaultSms = false,
                    isDefaultData = false,
                ),
            )
        )
        assertTrue(output.contains("eSIM"))
        assertFalse(output.contains("физическая"))
    }

    @Test
    fun defaultDecisionMarksOnlyTheMatchingRoles() {
        val voiceDefault = SimSlotInfo(1, 0, "A", "A", false, true, false, true)
        val smsDefault = SimSlotInfo(2, 1, "B", "B", false, false, true, false)
        val output = SimSlotsFormatter.format(listOf(voiceDefault, smsDefault))
        assertTrue(output.contains("slot=0") && output.contains("по умолчанию: звонки/данные"))
        assertTrue(output.contains("slot=1") && output.contains("по умолчанию: SMS"))
    }

    @Test
    fun rowWithNoDefaultRoleSaysNone() {
        val output = SimSlotsFormatter.format(
            listOf(SimSlotInfo(3, 0, "A", "A", false, false, false, false))
        )
        assertTrue(output.contains("по умолчанию: нет"))
    }

    @Test
    fun blankCarrierAndNameUseHonestPlaceholders() {
        val output = SimSlotsFormatter.format(
            listOf(SimSlotInfo(4, 0, null, "   ", false, false, false, false))
        )
        assertTrue(output.contains("(оператор неизвестен)"))
        assertTrue(output.contains("(без имени)"))
    }

    @Test
    fun noSimIsReportedHonestly() {
        val output = SimSlotsFormatter.format(emptyList())
        assertEquals(SimSlotsFormatter.NO_SIM_MESSAGE, output)
        assertTrue(output.contains("не найдены"))
    }

    @Test
    fun missingPermissionIsReportedHonestly() {
        assertEquals(SimSlotsFormatter.NO_PERMISSION_MESSAGE, SimSlotsFormatter.noPermission())
        assertTrue(SimSlotsFormatter.noPermission().contains("READ_PHONE_STATE"))
    }
}
