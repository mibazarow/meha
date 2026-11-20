package com.andharness.ru.data.assist

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted «Плавающий помощник» switch (the floating overlay bubble).
 *
 * Backed by plain [SharedPreferences] — the same pattern as `WatchdogConfigStore` / `ThemeStore`:
 * a non-secret boolean that must be readable synchronously from a boot receiver and from the
 * service itself, before any Compose frame exists. The in-memory [StateFlow] keeps the settings
 * toggle in sync without re-reading the file.
 *
 * Default is OFF ([DEFAULT_ENABLED]): showing a window on top of every other app is opt-in, and the
 * toggle only turns on after the user grants `SYSTEM_ALERT_WINDOW` (see `OverlayAssistantPolicy`).
 *
 * Reference: https://developer.android.com/reference/android/content/SharedPreferences
 */
@Singleton
class OverlayAssistantStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))

    /** Current switch value; collect it so the settings UI never drifts from the stored flag. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    companion object {
        const val PREFS_FILE = "andharness_overlay_assistant"

        /** Opt-in by design: a window over other apps is never enabled behind the user's back. */
        const val DEFAULT_ENABLED = false

        private const val KEY_ENABLED = "overlay_assistant_enabled"
    }
}
