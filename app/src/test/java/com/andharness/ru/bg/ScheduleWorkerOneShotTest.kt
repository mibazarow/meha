package com.andharness.ru.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A one-shot reminder is removed only when it was actually delivered successfully. A failed run is
 * NOT fulfilled, so its row must stay visible to the user. The rule is pure, so it is tested on the
 * JVM without Android/Robolectric (the full worker path still needs a device/instrumentation test).
 */
class ScheduleWorkerOneShotTest {

    @Test
    fun successfulOneShotDeletesItsRow() {
        assertTrue(
            "a delivered reminder (failure == null) must be removed from the schedule list",
            shouldDeleteOneShotRow(null)
        )
    }

    @Test
    fun failedOneShotKeepsItsRowVisible() {
        assertFalse(
            "a failed reminder must keep its row so the user still sees it",
            shouldDeleteOneShotRow("агент завершил с ошибкой (подробности в чате)")
        )
        assertFalse(
            "any non-null failure (even an empty message) must keep the row",
            shouldDeleteOneShotRow("")
        )
    }
}
