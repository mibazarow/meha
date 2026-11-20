package com.andharness.ru.ui.settings

import com.andharness.ru.data.prefs.ModelContextPreferences
import com.andharness.ru.data.style.StyleProfileStore
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles the destructive «Сбросить все данные» action: the confirmation flag, the chat wipe plus
 * LLM-settings reset, and the resulting catalog/context bookkeeping. State is written into the
 * shared [SettingsStateHolder]; [onCatalogCleared] lets the LLM controller drop its cached catalog.
 */
class SettingsResetController(
    private val state: SettingsStateHolder,
    private val scope: CoroutineScope,
    private val repository: ChatRepository,
    private val settingsStore: LlmSettingsStore,
    private val modelContextPreferences: ModelContextPreferences,
    /** Learned style profile; cleared with the rest of the user data. `null` in tests. */
    private val styleProfileStore: StyleProfileStore? = null,
    private val onCatalogCleared: () -> Unit,
) {

    fun confirmReset() {
        state.update { it.copy(showResetConfirmation = true) }
    }

    fun cancelReset() {
        state.update { it.copy(showResetConfirmation = false) }
    }

    fun resetAllData() {
        state.update { it.copy(isLoading = true, error = null) }
        scope.launch {
            try {
                repository.deleteAllChats()
                settingsStore.reset()
                modelContextPreferences.clear()
                styleProfileStore?.clear()
                onCatalogCleared()
                state.update {
                    it.copy(
                        // Profiles and the form are wiped too: the next configuration starts as
                        // a fresh draft (nothing is active until the user saves one).
                        endpoint = "",
                        model = "",
                        apiKey = "",
                        providerName = "",
                        providers = emptyList(),
                        activeProviderId = null,
                        editingProviderId = null,
                        providerDraft = true,
                        providerFormDirty = false,
                        models = emptyList(),
                        modelsFetched = false,
                        modelsError = null,
                        modelContextTokens = null,
                        modelMaxContextTokens = null,
                        manualContextTokens = "",
                        styleMode = com.andharness.ru.domain.style.StyleMode.AUTO,
                        styleTraits = emptyList(),
                        styleAddressForm = null,
                        styleCard = null,
                        styleManualCard = "",
                        styleManualCardDirty = false,
                        styleProfanityEnabled = true,
                        styleSamples = 0,
                    )
                }
            } catch (e: Exception) {
                state.update {
                    it.copy(isLoading = false, error = e.message ?: "Не удалось сбросить данные")
                }
            }
        }
    }
}
