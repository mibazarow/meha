package com.andharness.ru.domain.communications

import com.andharness.ru.domain.diagnostics.PowerGuardPolicy
import com.andharness.ru.domain.diagnostics.PowerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table of the background Communications gate: mode × power × daily budget →
 * RUN / SKIP / DEFER. Pure JVM, no Android.
 */
class CommunicationProcessGateTest {

    private fun snapshot(
        level: Int = 80,
        charging: Boolean = false,
        batterySaver: Boolean = false,
        thermal: Int = PowerGuardPolicy.THERMAL_STATUS_NONE,
    ) = PowerSnapshot(
        levelPercent = level,
        charging = charging,
        batterySaver = batterySaver,
        thermalStatus = thermal,
        batteryOptimizationExempt = false,
    )

    private fun unknown() = PowerSnapshot(
        levelPercent = -1,
        charging = false,
        batterySaver = false,
        thermalStatus = -1,
        batteryOptimizationExempt = false,
    )

    private val budget = 60L * 60 * 1000

    @Test
    fun `off is always skip even with power and budget`() {
        assertEquals(
            CommunicationProcessDecision.SKIP,
            CommunicationProcessGate.decide(CommunicationsMode.OFF, snapshot(charging = true), budget),
        )
        assertEquals(
            CommunicationProcessDecision.SKIP,
            CommunicationProcessGate.decide(
                CommunicationsMode.OFF,
                snapshot(level = 3, thermal = PowerGuardPolicy.THERMAL_STATUS_SEVERE),
                budget,
            ),
        )
    }

    @Test
    fun `exhausted budget is skip regardless of mode and power`() {
        assertEquals(
            CommunicationProcessDecision.SKIP,
            CommunicationProcessGate.decide(CommunicationsMode.ALWAYS, snapshot(), 0L),
        )
        assertEquals(
            CommunicationProcessDecision.SKIP,
            CommunicationProcessGate.decide(CommunicationsMode.CHARGING, snapshot(charging = true), -5L),
        )
    }

    @Test
    fun `charging mode runs only when charging`() {
        assertEquals(
            CommunicationProcessDecision.DEFER,
            CommunicationProcessGate.decide(CommunicationsMode.CHARGING, snapshot(charging = false), budget),
        )
        assertEquals(
            CommunicationProcessDecision.RUN,
            CommunicationProcessGate.decide(CommunicationsMode.CHARGING, snapshot(charging = true), budget),
        )
    }

    @Test
    fun `always mode runs while not charging when power is healthy`() {
        assertEquals(
            CommunicationProcessDecision.RUN,
            CommunicationProcessGate.decide(CommunicationsMode.ALWAYS, snapshot(level = 50), budget),
        )
    }

    @Test
    fun `critical power defers in both active modes`() {
        val severe = snapshot(charging = true, thermal = PowerGuardPolicy.THERMAL_STATUS_SEVERE)
        assertEquals(
            CommunicationProcessDecision.DEFER,
            CommunicationProcessGate.decide(CommunicationsMode.CHARGING, severe, budget),
        )
        assertEquals(
            CommunicationProcessDecision.DEFER,
            CommunicationProcessGate.decide(CommunicationsMode.ALWAYS, severe, budget),
        )

        val criticalBattery = snapshot(level = 3, charging = false)
        assertEquals(
            CommunicationProcessDecision.DEFER,
            CommunicationProcessGate.decide(CommunicationsMode.ALWAYS, criticalBattery, budget),
        )
    }

    @Test
    fun `unknown snapshot runs only in always mode`() {
        assertEquals(
            CommunicationProcessDecision.RUN,
            CommunicationProcessGate.decide(CommunicationsMode.ALWAYS, unknown(), budget),
        )
        assertEquals(
            CommunicationProcessDecision.DEFER,
            CommunicationProcessGate.decide(CommunicationsMode.CHARGING, unknown(), budget),
        )
        assertEquals(
            CommunicationProcessDecision.SKIP,
            CommunicationProcessGate.decide(CommunicationsMode.OFF, unknown(), budget),
        )
    }

    @Test
    fun `charging at any level is never treated as critical battery`() {
        // isBatteryCritical requires NOT charging, so a 3% charging device may still run.
        assertEquals(
            CommunicationProcessDecision.RUN,
            CommunicationProcessGate.decide(CommunicationsMode.CHARGING, snapshot(level = 3, charging = true), budget),
        )
    }
}
