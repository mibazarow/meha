package com.andharness.ru.data.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure post-check decision ([postCheckDecision]) that [UiMacroReplay.postCheck]
 * delegates to. [UiMacroReplay.postCheck] itself only adds the Android tree read on top, so this
 * cleanly exercises the abort/no-abort policy without an [android.accessibilityservice.AccessibilityService].
 *
 * A null `getRootInActiveWindow()` is `@Nullable` and happens during screen transitions, so it must
 * not be turned into a failure for non-`ui_type` steps.
 * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow()
 */
class UiMacroReplayPostCheckTest {

    @Test
    fun nonTypeStepWithUnavailableRootDoesNotFail() {
        val decision = postCheckDecision(
            tool = "ui_tap",
            rootAvailable = false,
            targetPresent = false,
            typeConfirmed = false,
        )
        assertNull(decision.failure)
        assertTrue(decision.hasSelector)
    }

    @Test
    fun typeStepWithUnavailableRootFails() {
        val decision = postCheckDecision(
            tool = "ui_type",
            rootAvailable = false,
            targetPresent = false,
            typeConfirmed = false,
        )
        assertNotNull(decision.failure)
    }

    @Test
    fun typeStepWithConfirmedTextDoesNotFail() {
        val decision = postCheckDecision(
            tool = "ui_type",
            rootAvailable = true,
            targetPresent = true,
            typeConfirmed = true,
        )
        assertNull(decision.failure)
        assertTrue(decision.typeConfirmed)
    }

    @Test
    fun typeStepWithUnconfirmedTextFails() {
        val decision = postCheckDecision(
            tool = "ui_type",
            rootAvailable = true,
            targetPresent = true,
            typeConfirmed = false,
        )
        assertNotNull(decision.failure)
    }

    @Test
    fun nonTypeStepWithAvailableRootNeverFails() {
        for (present in listOf(false, true)) {
            val decision = postCheckDecision(
                tool = "ui_scroll",
                rootAvailable = true,
                targetPresent = present,
                typeConfirmed = false,
            )
            assertNull(decision.failure)
            assertEquals(present, decision.targetPresent)
        }
    }
}
