package com.andharness.ru.domain.objective

import com.andharness.ru.domain.diagnostics.PowerGuardPolicy
import com.andharness.ru.domain.diagnostics.PowerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Power-guard downgrade of "parallel" objectives to one at a time (checklist §2). */
class ObjectiveConcurrencyPolicyTest {

    private fun snapshot(
        level: Int = 80,
        charging: Boolean = false,
        batterySaver: Boolean = false,
        thermal: Int = PowerGuardPolicy.THERMAL_STATUS_NONE
    ) = PowerSnapshot(
        levelPercent = level,
        charging = charging,
        batterySaver = batterySaver,
        thermalStatus = thermal,
        batteryOptimizationExempt = false
    )

    @Test
    fun `healthy device keeps the configured limit`() {
        assertEquals(2, ObjectiveConcurrencyPolicy.effectiveLimit(2, snapshot()))
        assertNull(ObjectiveConcurrencyPolicy.degradedReason(snapshot()))
    }

    @Test
    fun `configured limit is clamped to one through three`() {
        assertEquals(ObjectiveConcurrencyPolicy.MAX, ObjectiveConcurrencyPolicy.effectiveLimit(9, snapshot()))
        assertEquals(ObjectiveConcurrencyPolicy.MIN, ObjectiveConcurrencyPolicy.effectiveLimit(0, snapshot()))
    }

    @Test
    fun `battery saver downgrades to one`() {
        assertEquals(1, ObjectiveConcurrencyPolicy.effectiveLimit(3, snapshot(batterySaver = true)))
        assertNotNull(ObjectiveConcurrencyPolicy.degradedReason(snapshot(batterySaver = true)))
    }

    @Test
    fun `low battery while not charging downgrades to one`() {
        val low = snapshot(level = ObjectiveConcurrencyPolicy.LOW_BATTERY_PERCENT)
        assertEquals(1, ObjectiveConcurrencyPolicy.effectiveLimit(3, low))
        assertNotNull(ObjectiveConcurrencyPolicy.degradedReason(low))
    }

    @Test
    fun `low battery while charging keeps the configured limit`() {
        val lowCharging = snapshot(level = 10, charging = true)
        assertEquals(3, ObjectiveConcurrencyPolicy.effectiveLimit(3, lowCharging))
        assertNull(ObjectiveConcurrencyPolicy.degradedReason(lowCharging))
    }

    @Test
    fun `warm thermal status downgrades to one`() {
        val warm = snapshot(thermal = PowerGuardPolicy.THERMAL_STATUS_MODERATE)
        assertEquals(1, ObjectiveConcurrencyPolicy.effectiveLimit(3, warm))
        assertNotNull(ObjectiveConcurrencyPolicy.degradedReason(warm))
    }

    @Test
    fun `unknown snapshot is never treated as degraded`() {
        val unknown = PowerSnapshot(
            levelPercent = -1,
            charging = false,
            batterySaver = false,
            thermalStatus = -1,
            batteryOptimizationExempt = false
        )
        assertTrue(!ObjectiveConcurrencyPolicy.isDegraded(unknown))
        assertEquals(2, ObjectiveConcurrencyPolicy.effectiveLimit(2, unknown))
    }
}
