package com.andharness.ru.domain.automation

import com.andharness.ru.domain.automation.TriggerConfigs.HealthSensorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the health automation triggers (health checklist): parsing, the pure threshold /
 * dwell / steps-baseline / no-movement state machines, the honest sensor-availability decision and
 * the `sleep_window` heuristic. No Android APIs are touched.
 */
class HealthTriggerConfigsTest {

    // ------------------------------------------------------------------ heart_rate_threshold

    @Test
    fun heartParsingAcceptsAboveBelowAndBare() {
        val above = TriggerConfigs.parseHeartRate("above=100")
        assertEquals(100, above!!.above)
        assertNull(above.below)
        assertEquals(TriggerConfigs.DEFAULT_HEART_RATE_DWELL_SEC, above.dwellSec)

        val below = TriggerConfigs.parseHeartRate("below=50; dwell_sec=15")
        assertNull(below!!.above)
        assertEquals(50, below.below)
        assertEquals(15, below.dwellSec)

        assertEquals(120, TriggerConfigs.parseHeartRate("120")!!.above)
        assertEquals(90, TriggerConfigs.parseHeartRate("bpm_above=90")!!.above)
    }

    @Test
    fun heartParsingRejectsAmbiguousAndOutOfRange() {
        assertNull(TriggerConfigs.parseHeartRate(""))
        assertNull(TriggerConfigs.parseHeartRate("above=100; below=50")) // exactly one
        assertNull(TriggerConfigs.parseHeartRate("above=abc"))
        assertNull(TriggerConfigs.parseHeartRate("above=5")) // below MIN
        assertNull(TriggerConfigs.parseHeartRate("above=400")) // above MAX
        assertNull(TriggerConfigs.parseHeartRate("above=100; dwell_sec=99999"))
    }

    @Test
    fun heartDwellTrackerFiresOnceAndRearms() {
        val immediate = TriggerConfigs.HeartRateDwellTracker(TriggerConfigs.HeartRateSpec(100, null, 0))
        assertTrue(immediate.onSample(101, 1_000))
        assertFalse(immediate.onSample(105, 2_000)) // still high, already fired
        assertFalse(immediate.onSample(80, 3_000)) // condition broke, re-arms
        assertTrue(immediate.onSample(101, 4_000))

        val dwell = TriggerConfigs.HeartRateDwellTracker(TriggerConfigs.HeartRateSpec(100, null, 10))
        assertFalse(dwell.onSample(101, 0)) // hurdle starts
        assertFalse(dwell.onSample(101, 5_000)) // not yet 10 s
        assertTrue(dwell.onSample(101, 10_000)) // sustained -> fire
        assertFalse(dwell.onSample(101, 11_000))
    }

    @Test
    fun heartBelowUsesStrictComparison() {
        val spec = TriggerConfigs.HeartRateSpec(null, 50, 0)
        assertTrue(spec.holds(49))
        assertFalse(spec.holds(50))
        assertFalse(spec.holds(51))
    }

    // ------------------------------------------------------------------ steps_reached

    @Test
    fun stepsParsingAndCrossing() {
        assertEquals(5000L, TriggerConfigs.parseSteps("target=5000")!!.target)
        assertEquals(1000L, TriggerConfigs.parseSteps("1000")!!.target)
        assertNull(TriggerConfigs.parseSteps(""))
        assertNull(TriggerConfigs.parseSteps("target=0"))
        assertNull(TriggerConfigs.parseSteps("target=abc"))

        val spec = TriggerConfigs.StepsSpec(5000)
        assertFalse(spec.reached(1000, 5999))
        assertTrue(spec.reached(1000, 6000))
        assertTrue(spec.reached(1000, 7000))
        assertEquals(0L, spec.delta(5000, 4000)) // reboot reset the counter
        assertTrue(spec.reached(4000, 9000)) // delta = target
        assertFalse(spec.reached(4000, 8999))
    }

    // ------------------------------------------------------------------ no_movement

    @Test
    fun noMovementParsingAndStateMachine() {
        assertEquals(30, TriggerConfigs.parseNoMovement("minutes=30")!!.idleMinutes)
        assertEquals(45, TriggerConfigs.parseNoMovement("45")!!.idleMinutes)
        assertNull(TriggerConfigs.parseNoMovement(""))
        assertNull(TriggerConfigs.parseNoMovement("minutes=0"))
        assertNull(TriggerConfigs.parseNoMovement("minutes=5000"))

        val spec = TriggerConfigs.NoMovementSpec(30)
        val tracker = TriggerConfigs.NoMovementTracker(spec)
        assertFalse(tracker.shouldFire(0)) // no baseline yet
        tracker.onMotion(0)
        assertEquals(29L, tracker.idleMinutes(29 * 60_000L))
        assertFalse(tracker.shouldFire(29 * 60_000L))
        assertTrue(tracker.shouldFire(30 * 60_000L))
        assertFalse(tracker.shouldFire(31 * 60_000L)) // fired once per idle period
        tracker.onMotion(32 * 60_000L) // motion re-arms
        assertFalse(tracker.shouldFire(61 * 60_000L))
        assertTrue(tracker.shouldFire(62 * 60_000L))
    }

    // ------------------------------------------------------------------ availability decision

    @Test
    fun missingHealthSensorsIsHonest() {
        // Heart rate: only the HR sensor satisfies it.
        assertEquals(
            listOf(HealthSensorKind.HEART_RATE),
            TriggerConfigs.missingHealthSensors(
                AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD,
                setOf(HealthSensorKind.STEP_COUNTER)
            )
        )
        assertNull(
            TriggerConfigs.missingHealthSensors(
                AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD,
                setOf(HealthSensorKind.HEART_RATE)
            )
        )

        // no_movement: significant motion OR accelerometer.
        assertNull(
            TriggerConfigs.missingHealthSensors(
                AutomationSpec.TRIGGER_NO_MOVEMENT,
                setOf(HealthSensorKind.SIGNIFICANT_MOTION)
            )
        )
        assertNull(
            TriggerConfigs.missingHealthSensors(
                AutomationSpec.TRIGGER_NO_MOVEMENT,
                setOf(HealthSensorKind.ACCELEROMETER)
            )
        )
        val missing = TriggerConfigs.missingHealthSensors(AutomationSpec.TRIGGER_NO_MOVEMENT, emptySet())
        assertNotNull(missing)
        assertEquals(1, missing!!.size)

        // sleep_window needs the accelerometer (stillness heuristic).
        assertEquals(
            listOf(HealthSensorKind.ACCELEROMETER),
            TriggerConfigs.missingHealthSensors(
                AutomationSpec.TRIGGER_SLEEP_WINDOW,
                setOf(HealthSensorKind.STEP_COUNTER)
            )
        )

        // Non-health triggers are not gated.
        assertNull(TriggerConfigs.missingHealthSensors(AutomationSpec.TRIGGER_TIME, emptySet()))
    }

    // ------------------------------------------------------------------ sleep_window

    @Test
    fun sleepWindowParsingAndWrapAround() {
        val spec = TriggerConfigs.parseSleepWindow("start=23:00; end=07:00")
        assertNotNull(spec)
        assertEquals(23 * 60, spec!!.startMinute)
        assertEquals(7 * 60, spec.endMinute)
        assertTrue(spec.inWindow(23 * 60))
        assertTrue(spec.inWindow(2 * 60))
        assertTrue(spec.inWindow(7 * 60))
        assertFalse(spec.inWindow(12 * 60))

        val bare = TriggerConfigs.parseSleepWindow("22:30-06:30")
        assertEquals(22 * 60 + 30, bare!!.startMinute)

        assertNull(TriggerConfigs.parseSleepWindow(""))
        assertNull(TriggerConfigs.parseSleepWindow("start=23:00"))
        assertNull(TriggerConfigs.parseSleepWindow("start=99:00; end=07:00"))
    }

    @Test
    fun sleepHeuristicNeedsWindowScreenOffAndStillness() {
        val spec = TriggerConfigs.parseSleepWindow("start=23:00; end=07:00")!!
        assertTrue(spec.isSleeping(60, screenOff = true, still = true))
        assertFalse(spec.isSleeping(60, screenOff = false, still = true)) // screen on
        assertFalse(spec.isSleeping(60, screenOff = true, still = false)) // moving
        assertFalse(spec.isSleeping(12 * 60, screenOff = true, still = true)) // outside window
    }

    @Test
    fun formatMinuteOfDayPadsHours() {
        assertEquals("23:00", TriggerConfigs.formatMinuteOfDay(23 * 60))
        assertEquals("07:05", TriggerConfigs.formatMinuteOfDay(7 * 60 + 5))
    }

    // ------------------------------------------------------------------ spec wiring

    @Test
    fun newTriggersAreInTheCatalogAndValidated() {
        listOf(
            AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD,
            AutomationSpec.TRIGGER_STEPS_REACHED,
            AutomationSpec.TRIGGER_NO_MOVEMENT,
            AutomationSpec.TRIGGER_SLEEP_WINDOW
        ).forEach { trigger ->
            assertTrue("catalog is missing $trigger", AutomationSpec.TRIGGER_TYPES.contains(trigger))
            assertNotNull("sourceOf($trigger)", AutomationSpec.sourceOf(trigger))
            assertNotNull("normalizeTrigger($trigger)", AutomationSpec.normalizeTrigger(trigger))
        }

        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD, "above=110"))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD, ""))
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_STEPS_REACHED, "target=8000"))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_STEPS_REACHED, "target=x"))
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_NO_MOVEMENT, "minutes=20"))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_NO_MOVEMENT, "0"))
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_SLEEP_WINDOW, "start=23:00; end=07:00"))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_SLEEP_WINDOW, "start=23:00"))
    }

    @Test
    fun matchesReChecksThePureThresholds() {
        val heart = rule(AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD, "above=100")
        assertTrue(
            AutomationSpec.matches(
                heart,
                AutomationEvent(AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD, value = "101")
            )
        )
        assertFalse(
            AutomationSpec.matches(
                heart,
                AutomationEvent(AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD, value = "100")
            )
        )
        assertFalse(
            AutomationSpec.matches(
                heart,
                AutomationEvent(AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD, value = "garbage")
            )
        )

        val steps = rule(AutomationSpec.TRIGGER_STEPS_REACHED, "target=5000")
        assertTrue(
            AutomationSpec.matches(
                steps,
                AutomationEvent(AutomationSpec.TRIGGER_STEPS_REACHED, value = "5000")
            )
        )
        assertFalse(
            AutomationSpec.matches(
                steps,
                AutomationEvent(AutomationSpec.TRIGGER_STEPS_REACHED, value = "4999")
            )
        )

        val noMove = rule(AutomationSpec.TRIGGER_NO_MOVEMENT, "minutes=30")
        assertTrue(
            AutomationSpec.matches(
                noMove,
                AutomationEvent(AutomationSpec.TRIGGER_NO_MOVEMENT, value = "30")
            )
        )
        assertFalse(
            AutomationSpec.matches(
                noMove,
                AutomationEvent(AutomationSpec.TRIGGER_NO_MOVEMENT, value = "29")
            )
        )

        val sleep = rule(AutomationSpec.TRIGGER_SLEEP_WINDOW, "start=23:00; end=07:00")
        assertTrue(AutomationSpec.matches(sleep, AutomationEvent(AutomationSpec.TRIGGER_SLEEP_WINDOW)))
    }

    @Test
    fun describeTriggerMentionsTheHonestSleepHeuristic() {
        val text = AutomationSpec.describeTrigger(
            AutomationSpec.TRIGGER_SLEEP_WINDOW,
            "start=23:00; end=07:00"
        )
        assertTrue(text.contains("эвристика"))
        assertTrue(text.contains("23:00"))
        assertTrue(text.contains("не измерение сна"))
    }

    private fun rule(triggerType: String, config: String) = AutomationRule(
        id = 1,
        name = "test",
        triggerType = triggerType,
        triggerConfig = config,
        actionType = AutomationSpec.ACTION_NOTIFY,
        actionConfig = "t | x"
    )
}
