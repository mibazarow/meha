package com.andharness.ru.data.tools.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SimNumbersFormatter] — the pure, Android-free row mapping of `sim_numbers` and of
 * the `sim_numbers` device fact. No Android runtime: [SimNumberRow] rows are built by hand exactly
 * as [SimNumbersReader] builds them from `SubscriptionInfo`.
 *
 * Locks the honesty rule (BUG-056/BUG-057): a number that the platform did not return is reported
 * as «не определено», never invented.
 */
class SimNumbersFormatterTest {

    private fun row(
        number: String?,
        reason: String? = null,
        iccid: String? = "8970162010000000000",
        carrier: String? = "T-MOB 20G",
    ) = SimNumberRow(
        subscriptionId = 1,
        slotIndex = 0,
        carrierName = carrier,
        iccid = iccid,
        number = number,
        numberUnavailableReason = reason,
    )

    @Test
    fun knownNumberIsRenderedVerbatim() {
        val output = SimNumbersFormatter.format(listOf(row("+7 900 123-45-67")))
        assertTrue(output.contains("Номера SIM-подписок: 1"))
        assertTrue(output.contains("номер: +7 900 123-45-67"))
        assertFalse("a known number must not be flagged undefined", output.contains(SimNumbersFormatter.UNDEFINED))
    }

    @Test
    fun missingNumberIsHonestUndefined() {
        val output = SimNumbersFormatter.format(listOf(row(null)))
        assertTrue(output.contains("номер: ${SimNumbersFormatter.UNDEFINED}"))
        assertTrue(output.contains(SimNumbersFormatter.HONEST_NOTE))
    }

    @Test
    fun blankNumberCountsAsUndefined() {
        val output = SimNumbersFormatter.format(listOf(row("   ")))
        assertTrue(output.contains("номер: ${SimNumbersFormatter.UNDEFINED}"))
    }

    @Test
    fun missingNumbersPermissionIsNamedPerRow() {
        val output = SimNumbersFormatter.format(
            listOf(row(null, reason = "нет разрешения READ_PHONE_NUMBERS"))
        )
        assertTrue(output.contains("не определено (нет разрешения READ_PHONE_NUMBERS)"))
    }

    @Test
    fun blankCarrierAndIccidUseHonestPlaceholders() {
        val output = SimNumbersFormatter.format(
            listOf(SimNumberRow(2, 1, "  ", null, null))
        )
        assertTrue(output.contains("(оператор неизвестен)"))
        assertTrue(output.contains("ICCID ${SimNumbersFormatter.ICCID_UNAVAILABLE}"))
        assertTrue(output.contains("slot=1"))
        assertTrue(output.contains("id=2"))
    }

    @Test
    fun noSimIsReportedHonestly() {
        assertEquals(SimNumbersFormatter.NO_SIM_MESSAGE, SimNumbersFormatter.format(emptyList()))
        assertTrue(SimNumbersFormatter.format(emptyList()).contains("не найдены"))
    }

    @Test
    fun missingPhoneStateIsReportedHonestly() {
        assertEquals(SimNumbersFormatter.NO_PERMISSION_MESSAGE, SimNumbersFormatter.noPermission())
        assertTrue(SimNumbersFormatter.noPermission().contains("READ_PHONE_STATE"))
    }

    @Test
    fun factJsonMarksAKnownNumber() {
        val json = SimNumbersFormatter.toFactJson(listOf(row("+79001234567"))).toString()
        assertTrue(json.contains("\"numberKnown\":true"))
        assertTrue(json.contains("+79001234567"))
        assertFalse(json.contains(SimNumbersFormatter.UNDEFINED))
    }

    @Test
    fun factJsonMarksUndefinedNumberWithoutInventing() {
        val json = SimNumbersFormatter.toFactJson(listOf(row(null))).toString()
        assertTrue(json.contains("\"numberKnown\":false"))
        assertTrue(json.contains("\"number\":\"${SimNumbersFormatter.UNDEFINED}\""))
        assertTrue("payload must explain why the number is missing", json.contains("\"note\":\""))
    }

    @Test
    fun factJsonHasNoNoteWhenTheNumberIsKnown() {
        val json = SimNumbersFormatter.toFactJson(listOf(row("+79001234567"))).toString()
        assertFalse(json.contains("\"note\":"))
    }
}
