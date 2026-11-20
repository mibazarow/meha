package com.andharness.ru.ui.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedSettingsHintTest {

    @Test
    fun sideloadedAppWithBlockedAccessOnAndroid13GetsTheExplanation() {
        assertTrue(RestrictedSettingsHint.shouldShow(33, null, sensitiveAccessDenied = true))
        assertTrue(RestrictedSettingsHint.shouldShow(34, "com.example.browser", true))
        assertTrue(RestrictedSettingsHint.shouldShow(36, null, true))
    }

    @Test
    fun storeInstallsDoNotSeeTheExplanation() {
        listOf("com.android.vending", "com.huawei.appmarket").forEach { store ->
            assertFalse(store, RestrictedSettingsHint.shouldShow(34, store, true))
        }
    }

    @Test
    fun nothingIsShownWhenAccessIsAlreadyGranted() {
        assertFalse(RestrictedSettingsHint.shouldShow(34, null, sensitiveAccessDenied = false))
    }

    @Test
    fun olderAndroidHasNoRestrictedSettingsGate() {
        assertFalse(RestrictedSettingsHint.shouldShow(32, null, sensitiveAccessDenied = true))
    }
}
