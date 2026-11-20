package com.andharness.ru.ui.theme

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted, app-wide theme selection (D3: dark by default, live toggle).
 *
 * Backed by plain [SharedPreferences] — the same pattern as `WatchdogConfigStore` /
 * `MessagingIndexConfigStore`: a non-secret flag that must be readable synchronously when the UI
 * starts (so the very first frame already has the right theme, with no light-to-dark flash).
 * The in-memory [StateFlow] keeps Compose observers in sync without re-reading the file.
 *
 * Reference: https://developer.android.com/reference/android/content/SharedPreferences
 */
@Singleton
class ThemeStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(readMode())

    /** Current selection; collect it to recompose the app when the user toggles the theme. */
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun setMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private fun readMode(): ThemeMode {
        val raw = prefs.getString(KEY_MODE, null) ?: return DEFAULT_MODE
        return ThemeMode.values().firstOrNull { it.name == raw } ?: DEFAULT_MODE
    }

    companion object {
        const val PREFS_FILE = "andharness_theme"
        private const val KEY_MODE = "theme_mode"

        /** D3: dark by default. */
        val DEFAULT_MODE = ThemeMode.DARK
    }
}
