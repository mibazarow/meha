package com.andharness.ru.domain.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [UiConsentPolicy]: the UI-consent wait must fit inside Android's shortest documented
 * broadcast ANR window, and the wait must only be attempted when a foreground Activity can host the
 * system dialog. Pure logic, no Android runtime needed.
 */
class UiConsentPolicyTest {

    @Test
    fun consentTimeoutFitsInsideBroadcastAnrWindow() {
        assertTrue(UiConsentPolicy.isWithinAnrWindow())
        assertTrue(UiConsentPolicy.TIMEOUT_MS < UiConsentPolicy.BROADCAST_ANR_WINDOW_MS)
    }

    @Test
    fun timeoutAtOrAboveTheWindowIsNotAnrSafe() {
        assertFalse(UiConsentPolicy.isWithinAnrWindow(UiConsentPolicy.BROADCAST_ANR_WINDOW_MS))
        assertFalse(UiConsentPolicy.isWithinAnrWindow(120_000L))
        assertFalse(UiConsentPolicy.isWithinAnrWindow(0L))
    }

    @Test
    fun uiWaitIsOnlyAllowedWhenAppIsForeground() {
        assertTrue(UiConsentPolicy.canAwaitUi(appForeground = true))
        assertFalse(UiConsentPolicy.canAwaitUi(appForeground = false))
    }
}
