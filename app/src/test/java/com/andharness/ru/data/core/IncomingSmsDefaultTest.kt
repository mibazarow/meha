package com.andharness.ru.data.core

import com.andharness.ru.ui.settings.SettingsUiState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Обрабатывать входящие SMS» is ON by default (BUG-006): a fresh install must process incoming SMS
 * without any user action. The SharedPreferences fallback in [WatchdogConfigStore] is only consulted
 * when the key is absent, so an explicitly stored `false` is preserved for existing users.
 */
class IncomingSmsDefaultTest {

    @Test
    fun interceptionFlagDefaultsOn() {
        assertTrue(WatchdogConfigStore.DEFAULT_INTERCEPTION_ENABLED)
    }

    @Test
    fun settingsUiStateDefaultsIncomingSmsOn() {
        assertTrue(SettingsUiState().incomingSmsEnabled)
    }
}
