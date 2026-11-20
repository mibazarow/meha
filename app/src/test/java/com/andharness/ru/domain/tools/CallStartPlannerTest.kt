package com.andharness.ru.domain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStartPlannerTest {

    @Test
    fun `blank or missing number is rejected before anything else`() {
        assertEquals(CallStartPlan.BlankNumber, CallStartPlanner.plan(null, true))
        assertEquals(CallStartPlan.BlankNumber, CallStartPlanner.plan("", true))
        assertEquals(CallStartPlan.BlankNumber, CallStartPlanner.plan("   ", true))
        // Blank wins over a missing permission: the argument is the user's first mistake.
        assertEquals(CallStartPlan.BlankNumber, CallStartPlanner.plan("  ", false))
    }

    @Test
    fun `missing permission yields the permission plan`() {
        assertEquals(CallStartPlan.NeedsPermission, CallStartPlanner.plan("+79990000000", false))
    }

    @Test
    fun `granted permission yields a place plan with the trimmed number`() {
        val plan = CallStartPlanner.plan("  +7 999 123-45-67  ", true)
        assertEquals(CallStartPlan.Place("+7 999 123-45-67", false), plan)
    }

    @Test
    fun `emergency numbers are flagged but still placed`() {
        listOf("112", "911", "999", " 1-1-2 ").forEach { raw ->
            val plan = CallStartPlanner.plan(raw, true)
            assertTrue("$raw should be emergency", (plan as CallStartPlan.Place).emergency)
        }
    }

    @Test
    fun `normal numbers are not flagged as emergency`() {
        listOf("+79991234567", "5551234", "88005553535").forEach { raw ->
            val plan = CallStartPlanner.plan(raw, true)
            assertFalse("$raw should not be emergency", (plan as CallStartPlan.Place).emergency)
        }
    }

    @Test
    fun `normalizeDigits strips separators but keeps a leading plus`() {
        assertEquals("+79991234567", CallStartPlanner.normalizeDigits("+7 (999) 123-45-67"))
        assertEquals("112", CallStartPlanner.normalizeDigits(" 1 1 2 "))
    }

    @Test
    fun `success message names the exact api used`() {
        val telecom = CallStartPlanner.successMessage("123", CallApi.TELECOM_PLACE_CALL, false)
        assertTrue(telecom.contains("TelecomManager.placeCall"))
        assertTrue(telecom.contains("123"))
        assertFalse(telecom.contains("ACTION_DIAL"))

        val actionCall = CallStartPlanner.successMessage("123", CallApi.ACTION_CALL, false)
        assertTrue(actionCall.contains("Intent.ACTION_CALL"))
    }

    @Test
    fun `emergency success message carries the confirmation caveat`() {
        val message = CallStartPlanner.successMessage("112", CallApi.TELECOM_PLACE_CALL, true)
        assertTrue(message.contains("подтвержд"))
    }

    @Test
    fun `failure message includes the real detail when present`() {
        assertTrue(
            CallStartPlanner.failureMessage("123", CallApi.ACTION_CALL, "no handler")
                .contains("no handler")
        )
        assertFalse(
            CallStartPlanner.failureMessage("123", CallApi.ACTION_CALL, null).contains(":")
        )
    }

    @Test
    fun `dial only is never advertised as a successful call path`() {
        // Guards the regression this bug is about: ACTION_DIAL must not be used to claim a call.
        val message = CallStartPlanner.successMessage("123", CallApi.TELECOM_PLACE_CALL, false)
        assertFalse(message.contains("ACTION_DIAL"))
        assertFalse(CallApi.TELECOM_PLACE_CALL.label.contains("DIAL"))
        assertFalse(CallApi.ACTION_CALL.label.contains("DIAL"))
    }
}
