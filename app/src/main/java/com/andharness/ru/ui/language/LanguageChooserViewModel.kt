package com.andharness.ru.ui.language

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.domain.locale.LocaleApplier
import com.andharness.ru.domain.settings.LlmSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the first-run language chooser — the very first screen, BEFORE the license.
 *
 * The choice is (1) persisted — the language via [LlmSettingsStore.updateLanguage] and the dedicated
 * "explicitly chosen" flag via [LlmSettingsStore.markLanguageChosen] — and (2) applied immediately
 * through the [LocaleApplier] seam (`AppCompatDelegate.setApplicationLocales`, which recreates the
 * Activity), so the licence and every following screen render in the chosen language. [chosen] flips
 * to `true` on selection; the screen uses it to navigate on (and to survive that Activity
 * recreation, after which this ViewModel is retained and the restored back stack still shows the
 * chooser).
 *
 * [chosen] starts `false` on EVERY launch (it is NOT seeded from the persisted flag): the first-run
 * gate is «a model connection is saved», and until it is, the language step must be shown and chosen
 * again each launch rather than skipped just because a language was picked in an earlier session.
 * The persisted language value still applies the locale for the session.
 *
 * Docs: per-app language preferences —
 * https://developer.android.com/guide/topics/resources/app-languages
 */
@HiltViewModel
class LanguageChooserViewModel @Inject constructor(
    private val settingsStore: LlmSettingsStore,
    private val localeApplier: LocaleApplier,
) : ViewModel() {

    private val _chosen = MutableStateFlow(false)

    /** `true` once a language has been chosen in THIS session. */
    val chosen: StateFlow<Boolean> = _chosen.asStateFlow()

    /**
     * Commits [tag] as the interface language. One-shot: a second call is ignored, so the
     * recreate-driven recomposition cannot re-fire navigation with a different value.
     */
    fun choose(tag: String) {
        if (_chosen.value) return
        val normalized = AppLanguage.normalize(tag)
        _chosen.value = true
        // Persist first (the Main.immediate scope runs this to completion before returning), so the
        // gate `hasChosenLanguage()` — backed by the dedicated flag `markLanguageChosen()` — is
        // already true when the locale change recreates the Activity.
        viewModelScope.launch {
            runCatching {
                settingsStore.updateLanguage(normalized)
                settingsStore.markLanguageChosen()
            }
        }
        localeApplier.apply(normalized)
    }
}
