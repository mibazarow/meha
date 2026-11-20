package com.andharness.ru.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerGuardPolicyTest {

    private fun snapshot(
        levelPercent: Int = 80,
        charging: Boolean = false,
        batterySaver: Boolean = false,
        thermalStatus: Int = PowerGuardPolicy.THERMAL_STATUS_NONE,
        batteryOptimizationExempt: Boolean = true
    ) = PowerSnapshot(
        levelPercent = levelPercent,
        charging = charging,
        batterySaver = batterySaver,
        thermalStatus = thermalStatus,
        batteryOptimizationExempt = batteryOptimizationExempt
    )

    @Test
    fun healthyDeviceAllowsWork() {
        val verdict = PowerGuardPolicy.evaluate(snapshot())
        assertEquals(PowerAction.ALLOW, verdict.action)
        assertFalse(verdict.defer)
        assertNull(verdict.reason)
        assertTrue(verdict.snapshot.known)
    }

    @Test
    fun criticalBatteryNotChargingDefers() {
        val verdict = PowerGuardPolicy.evaluate(snapshot(levelPercent = 5, charging = false))
        assertEquals(PowerAction.DEFER, verdict.action)
        assertTrue(verdict.defer)
        assertNotNull(verdict.reason)
        assertTrue(verdict.reason!!, verdict.reason!!.contains("5%"))
        assertTrue(verdict.reason!!, verdict.reason!!.contains("не заряжается"))
    }

    @Test
    fun criticalBatteryWhileChargingAllows() {
        val verdict = PowerGuardPolicy.evaluate(snapshot(levelPercent = 3, charging = true))
        assertEquals(PowerAction.ALLOW, verdict.action)
        assertNull(verdict.reason)
    }

    @Test
    fun justAboveTheThresholdAllows() {
        val verdict = PowerGuardPolicy.evaluate(snapshot(levelPercent = 6, charging = false))
        assertEquals(PowerAction.ALLOW, verdict.action)
    }

    @Test
    fun zeroPercentDefers() {
        val verdict = PowerGuardPolicy.evaluate(snapshot(levelPercent = 0, charging = false))
        assertEquals(PowerAction.DEFER, verdict.action)
    }

    @Test
    fun severeThermalDefers() {
        val verdict = PowerGuardPolicy.evaluate(
            snapshot(thermalStatus = PowerGuardPolicy.THERMAL_STATUS_SEVERE)
        )
        assertEquals(PowerAction.DEFER, verdict.action)
        assertTrue(verdict.reason!!, verdict.reason!!.contains("сильный нагрев"))
    }

    @Test
    fun moderateThermalAllows() {
        val verdict = PowerGuardPolicy.evaluate(
            snapshot(thermalStatus = PowerGuardPolicy.THERMAL_STATUS_MODERATE)
        )
        assertEquals(PowerAction.ALLOW, verdict.action)
    }

    @Test
    fun emergencyThermalDefersAndNamesTheStatus() {
        val verdict = PowerGuardPolicy.evaluate(
            snapshot(thermalStatus = PowerGuardPolicy.THERMAL_STATUS_EMERGENCY)
        )
        assertEquals(PowerAction.DEFER, verdict.action)
        assertTrue(verdict.reason!!, verdict.reason!!.contains("аварийный нагрев"))
    }

    @Test
    fun bothRulesAreReportedTogether() {
        val verdict = PowerGuardPolicy.evaluate(
            snapshot(levelPercent = 2, charging = false, thermalStatus = PowerGuardPolicy.THERMAL_STATUS_CRITICAL)
        )
        assertEquals(PowerAction.DEFER, verdict.action)
        assertTrue(verdict.reason!!, verdict.reason!!.contains("2%"))
        assertTrue(verdict.reason!!, verdict.reason!!.contains("критический нагрев"))
    }

    @Test
    fun userRequestedRunIsNeverDeferred() {
        val crucial = snapshot(levelPercent = 1, charging = false, thermalStatus = PowerGuardPolicy.THERMAL_STATUS_SHUTDOWN)
        val verdict = PowerGuardPolicy.evaluate(crucial, essential = true)
        assertEquals(PowerAction.ALLOW, verdict.action)
        assertNull(verdict.reason)
        assertEquals(crucial, verdict.snapshot)
    }

    @Test
    fun unknownSnapshotIsAllowedInsteadOfGuessed() {
        val unknown = PowerSnapshot(
            levelPercent = -1,
            charging = false,
            batterySaver = false,
            thermalStatus = -1,
            batteryOptimizationExempt = false
        )
        assertFalse(unknown.known)
        val verdict = PowerGuardPolicy.evaluate(unknown)
        assertEquals(PowerAction.ALLOW, verdict.action)
        assertNull(verdict.reason)
    }

    @Test
    fun thermalLabelsCoverEveryDocumentedStatus() {
        for (status in 0..6) {
            val label = PowerGuardPolicy.thermalLabel(status)
            assertFalse("status $status must have a real label", label.startsWith("неизвестный"))
        }
        assertEquals("недоступно (API < 29)", PowerGuardPolicy.thermalLabel(-1))
        assertTrue(PowerGuardPolicy.thermalLabel(42).contains("42"))
    }

    @Test
    fun helperPredicatesMatchTheRules() {
        assertTrue(PowerGuardPolicy.isThermalCritical(PowerGuardPolicy.THERMAL_STATUS_SEVERE))
        assertTrue(PowerGuardPolicy.isThermalCritical(PowerGuardPolicy.THERMAL_STATUS_SHUTDOWN))
        assertFalse(PowerGuardPolicy.isThermalCritical(PowerGuardPolicy.THERMAL_STATUS_MODERATE))
        assertTrue(PowerGuardPolicy.isBatteryCritical(snapshot(levelPercent = 5, charging = false)))
        assertFalse(PowerGuardPolicy.isBatteryCritical(snapshot(levelPercent = 5, charging = true)))
        assertFalse(PowerGuardPolicy.isBatteryCritical(snapshot(levelPercent = 55, charging = false)))
    }
}
