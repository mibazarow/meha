package com.andharness.ru.data.core

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted feature switches that decide whether the background core may run (update.md §1.3,
 * "авто-старт только если включена хотя бы одна подсистема").
 *
 * - [coreEnabled] — the user explicitly enabled the core itself (settings toggle).
 * - [interceptionEnabled] — the "interception" subsystem (calls/SMS/notifications, Phase 2/4).
 *   Defaults to `true` ([DEFAULT_INTERCEPTION_ENABLED]) so a fresh install processes incoming SMS
 *   out of the box; the [SharedPreferences] fallback only applies when the key is absent, so an
 *   explicitly stored `false` (user toggled it off) is always preserved.
 *
 * Automation rules being enabled is a third, dynamic subsystem and is checked separately against
 * `AutomationRuleRepository`. Backed by plain [SharedPreferences]: these are non-secret flags that
 * must be readable synchronously from a boot receiver / crash handler.
 */
@Singleton
class WatchdogConfigStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _coreEnabled = MutableStateFlow(prefs.getBoolean(KEY_CORE_ENABLED, false))
    val coreEnabled: StateFlow<Boolean> = _coreEnabled.asStateFlow()

    private val _interceptionEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_INTERCEPTION_ENABLED, DEFAULT_INTERCEPTION_ENABLED))
    val interceptionEnabled: StateFlow<Boolean> = _interceptionEnabled.asStateFlow()

    fun isCoreEnabled(): Boolean = _coreEnabled.value

    fun isInterceptionEnabled(): Boolean = _interceptionEnabled.value

    fun setInterceptionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INTERCEPTION_ENABLED, enabled).apply()
        _interceptionEnabled.value = enabled
    }

    companion object {
        const val PREFS_FILE = "andharness_watchdog"

        /**
         * Fallback used only when [KEY_INTERCEPTION_ENABLED] is absent from [SharedPreferences],
         * i.e. a fresh install. Existing users keep whatever they explicitly stored, including
         * `false`.
         */
        const val DEFAULT_INTERCEPTION_ENABLED = true
        private const val KEY_CORE_ENABLED = "core_enabled"
        private const val KEY_INTERCEPTION_ENABLED = "interception_enabled"
    }
}
