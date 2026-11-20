package com.andharness.ru.ui.theme

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Screen-facing access to the persisted theme selection: read [mode] and set one of the three
 * explicitly from the Settings screen (design decision D3, redesign §2). A ViewModel is used instead
 * of reaching for [ThemeStore] directly so composables stay Hilt-injected through the usual
 * `hiltViewModel()` path.
 */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val store: ThemeStore
) : ViewModel() {

    val mode: StateFlow<ThemeMode> = store.mode

    /**
     * Persists an explicit [ThemeMode] selection and updates [mode] for every observer. All three
     * modes ([ThemeMode.LIGHT], [ThemeMode.DARK], [ThemeMode.SYSTEM]) are first-class; [ThemeMode.SYSTEM]
     * is resolved against the OS setting when the theme is applied (see `Theme`/`MainActivity`).
     */
    fun setMode(mode: ThemeMode) {
        store.setMode(mode)
    }
}
