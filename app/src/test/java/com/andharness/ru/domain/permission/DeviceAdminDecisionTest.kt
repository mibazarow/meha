package com.andharness.ru.domain.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [DeviceAdminDecision] (BUG-021): `device_admin_enable` must only report success
 * when the admin component is actually active, never merely because an intent was dispatched.
 * Pure logic, no Android runtime needed.
 */
class DeviceAdminDecisionTest {

    @Test
    fun alreadyActiveIsSuccess() {
        val result = DeviceAdminDecision.alreadyActive()
        assertTrue(result.success)
        assertEquals(DeviceAdminDecision.ALREADY_ACTIVE, result.message)
    }

    @Test
    fun activeAfterTheScreenIsSuccess() {
        val result = DeviceAdminDecision.enable(
            adminActiveBefore = false,
            outcome = DeviceAdminUiOutcome.COMPLETED,
            adminActiveAfter = true,
        )
        assertTrue(result.success)
        assertEquals(DeviceAdminDecision.ACTIVATED, result.message)
    }

    @Test
    fun screenClosedWithoutActivationIsAnHonestFailure() {
        // The Samsung regression: the intent was accepted but the admin never became active.
        val result = DeviceAdminDecision.enable(
            adminActiveBefore = false,
            outcome = DeviceAdminUiOutcome.COMPLETED,
            adminActiveAfter = false,
        )
        assertFalse(result.success)
        assertEquals(DeviceAdminDecision.NOT_CONFIRMED, result.message)
    }

    @Test
    fun cancelledIsFailure() {
        val result = DeviceAdminDecision.enable(
            adminActiveBefore = false,
            outcome = DeviceAdminUiOutcome.CANCELLED,
            adminActiveAfter = false,
        )
        assertFalse(result.success)
        assertEquals(DeviceAdminDecision.CANCELLED, result.message)
    }

    @Test
    fun failedKeepsTheBridgeFailureMessageHonest() {
        val noUi = "Диалог можно показать только когда приложение на переднем плане."
        val result = DeviceAdminDecision.enable(
            adminActiveBefore = false,
            outcome = DeviceAdminUiOutcome.FAILED,
            adminActiveAfter = false,
            failureMessage = noUi,
        )
        assertFalse(result.success)
        assertEquals(noUi, result.message)
    }

    @Test
    fun failedWithoutAMessageFallsBackToUnexpected() {
        val result = DeviceAdminDecision.enable(
            adminActiveBefore = false,
            outcome = DeviceAdminUiOutcome.FAILED,
            adminActiveAfter = false,
        )
        assertFalse(result.success)
        assertEquals(DeviceAdminDecision.UNEXPECTED, result.message)
    }

    @Test
    fun unexpectedIsFailure() {
        val result = DeviceAdminDecision.enable(
            adminActiveBefore = false,
            outcome = DeviceAdminUiOutcome.UNEXPECTED,
            adminActiveAfter = false,
        )
        assertFalse(result.success)
        assertEquals(DeviceAdminDecision.UNEXPECTED, result.message)
    }
}
