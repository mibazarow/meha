package com.andharness.ru.ui.settings

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.data.assist.OverlayAssistantStore
import com.andharness.ru.data.communications.CommunicationsConfigStore
import com.andharness.ru.data.communications.speech.SpeechModelDownloadScheduler
import com.andharness.ru.data.communications.speech.SpeechModelStore
import com.andharness.ru.data.core.WatchdogConfigStore
import com.andharness.ru.data.llm.ModelsCatalogClient
import com.andharness.ru.data.prefs.ModelContextPreferences
import com.andharness.ru.data.update.InAppUpdater
import com.andharness.ru.data.style.StyleProfileStore
import com.andharness.ru.domain.style.StyleActivation
import com.andharness.ru.domain.style.StyleMode
import com.andharness.ru.domain.style.StyleTraitKey
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.communications.CommunicationsMode
import com.andharness.ru.domain.communications.speech.SpeechModel
import com.andharness.ru.domain.communications.speech.SpeechModelDownloadState
import com.andharness.ru.domain.communications.speech.SpeechModelKind
import com.andharness.ru.domain.llm.LlmAuthStyle
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.domain.locale.AppLocale
import com.andharness.ru.domain.settings.ContextLimitPolicy
import com.andharness.ru.domain.settings.LlmSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel of the settings screen. It owns the observable [SettingsUiState] through a
 * [SettingsStateHolder] and delegates each responsibility to a focused collaborator:
 * [LlmSettingsController] (endpoint/key/auth/model catalog/context/save), [UpdateController]
 * (in-app updater), [OverlayAssistantController] (floating assistant) and
 * [SettingsResetController] (destructive reset). The public members below are the thin,
 * source-compatible delegation surface the screen observes.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: LlmSettingsStore,
    private val repository: ChatRepository,
    private val modelsCatalogClient: ModelsCatalogClient,
    private val modelContextPreferences: ModelContextPreferences,
    private val inAppUpdater: InAppUpdater,
    private val watchdogConfigStore: WatchdogConfigStore,
    private val overlayAssistantStore: OverlayAssistantStore,
    private val communicationsConfigStore: CommunicationsConfigStore,
    private val speechModelStore: SpeechModelStore,
    private val speechModelDownloadScheduler: SpeechModelDownloadScheduler,
    private val styleProfileStore: StyleProfileStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val stateHolder = SettingsStateHolder(
        SettingsUiState(
            incomingSmsEnabled = watchdogConfigStore.isInterceptionEnabled(),
            overlayAssistantEnabled = overlayAssistantStore.isEnabled(),
            overlayAssistantPermissionGranted = Settings.canDrawOverlays(appContext),
            communicationsMode = communicationsConfigStore.currentMode(),
            communicationsKeepAudioDays = communicationsConfigStore.currentKeepAudioDays(),
            communicationsAudioCacheLimitMb = communicationsConfigStore.currentAudioCacheLimitMb(),
            communicationsMaxProcessMinutesPerDay =
                communicationsConfigStore.currentMaxProcessMinutesPerDay(),
            communicationsWhisperModelId = communicationsConfigStore.currentWhisperModelId(),
            communicationsWhisperLanguage = communicationsConfigStore.currentWhisperLanguage(),
            communicationsSendTranscriptsToCloud = communicationsConfigStore.isSendTranscriptsToCloud(),
            communicationsDiarizationEnabled = communicationsConfigStore.isDiarizationEnabled(),
        )
    )
    val uiState: StateFlow<SettingsUiState> = stateHolder.state

    // --- Responsibility-specific collaborators ----------------------------------------------
    private val llm = LlmSettingsController(
        state = stateHolder,
        scope = viewModelScope,
        settingsStore = settingsStore,
        modelsCatalogClient = modelsCatalogClient,
        modelContextPreferences = modelContextPreferences,
    )

    private val updater = UpdateController(
        state = stateHolder,
        scope = viewModelScope,
        appContext = appContext,
        updater = inAppUpdater,
    )

    private val overlay = OverlayAssistantController(
        state = stateHolder,
        appContext = appContext,
        overlayAssistantStore = overlayAssistantStore,
    )

    private val reset = SettingsResetController(
        state = stateHolder,
        scope = viewModelScope,
        repository = repository,
        settingsStore = settingsStore,
        modelContextPreferences = modelContextPreferences,
        styleProfileStore = styleProfileStore,
        onCatalogCleared = { llm.clearCatalog() },
    )

    init {
        viewModelScope.launch {
            settingsStore.config.collect { settings ->
                stateHolder.update { state ->
                    // The context is auto-derived from the provider maximum (or the fixed default),
                    // with the optional manual override seeded from the last saved value.
                    val max = llm.maxContextFor(settings.model)
                    val manual = state.manualContextTokens.ifEmpty {
                        modelContextPreferences.modelContextTokens()?.toString().orEmpty()
                    }
                    // Never clobber what the user is typing: while the form is a NEW draft or has
                    // unsaved edits, only non-form fields (active id/language/etc.) are refreshed.
                    val keepForm = state.providerDraft || state.providerFormDirty
                    val activeName = state.providers
                        .firstOrNull { it.id == settings.activeProviderId }
                        ?.name
                    state.copy(
                        endpoint = if (keepForm) state.endpoint else settings.endpoint,
                        model = if (keepForm) state.model else settings.model,
                        providerName = if (keepForm) state.providerName else activeName ?: state.providerName,
                        editingProviderId =
                            if (keepForm) state.editingProviderId
                            else state.editingProviderId ?: settings.activeProviderId,
                        activeProviderId = settings.activeProviderId,
                        semanticSearchEnabled = settings.semanticSearchEnabled,
                        reasoningEffort = settings.reasoningEffort,
                        language = settings.language,
                        isLoading = false,
                        manualContextTokens = manual,
                        modelContextTokens =
                            ContextLimitPolicy.effectiveContext(manual.toLongOrNull(), max),
                        modelMaxContextTokens = max,
                    )
                }
            }
        }
        // Saved provider profiles: mirrored for the list. The display name of the active profile
        // seeds the form only when it is not a draft and has no unsaved edits.
        viewModelScope.launch {
            settingsStore.providers.collect { providers ->
                stateHolder.update { state ->
                    val keepForm = state.providerDraft || state.providerFormDirty
                    val activeName = providers
                        .firstOrNull { it.id == state.activeProviderId }
                        ?.name
                    state.copy(
                        providers = providers,
                        providerName =
                            if (keepForm || activeName == null) {
                                state.providerName
                            } else {
                                activeName
                            },
                        // A profile list arriving after the config emission still binds the form
                        // to the active profile, so Save updates it instead of creating a duplicate.
                        editingProviderId =
                            if (keepForm) state.editingProviderId
                            else state.editingProviderId ?: state.activeProviderId,
                    )
                }
            }
        }
        // Learned communication style: one source of truth shared with the prompt builder and the
        // per-message observer. The manual card is not clobbered while the user is editing it.
        viewModelScope.launch {
            styleProfileStore.profile.collect { profile ->
                val active = StyleActivation.activeTraits(profile, limit = StyleTraitKey.entries.size)
                val address = active.firstOrNull { it.key == StyleTraitKey.ADDRESS }
                stateHolder.update { state ->
                    state.copy(
                        styleMode = profile.mode,
                        styleTraits = active.map { it.key },
                        styleAddressForm = address?.let { StyleActivation.addressForm(it) },
                        styleCard = profile.card,
                        styleManualCard =
                            if (state.styleManualCardDirty) state.styleManualCard
                            else profile.manualCard.orEmpty(),
                        styleProfanityEnabled = profile.profanityEnabled,
                        styleSamples = profile.samples,
                    )
                }
            }
        }
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) { settingsStore.getApiKey() }.orEmpty()
            stateHolder.update { it.copy(apiKey = key) }
        }
        viewModelScope.launch {
            val auth = withContext(Dispatchers.IO) { settingsStore.getAuthConfig() }
            stateHolder.update {
                it.copy(
                    authStyle = auth.style,
                    authHeaderName = auth.headerName,
                    authQueryParam = auth.queryParam,
                )
            }
        }
        viewModelScope.launch {
            val settings = settingsStore.config.first()
            val storedKey = withContext(Dispatchers.IO) { settingsStore.getApiKey() }.orEmpty()
            // Auto-load once on open only when a key is already stored. An endpoint without a key
            // waits for the user to enter one or to press «Обновить список» — no premature 401.
            if (settings.endpoint.isNotBlank() && storedKey.isNotBlank()) {
                llm.loadModels(settings.endpoint, force = false)
            }
        }
        // Updater: mirror the updater's single StateFlow and read the installed version once.
        viewModelScope.launch {
            stateHolder.update { it.copy(updaterState = inAppUpdater.state.value) }
            inAppUpdater.state.collect { updaterState ->
                stateHolder.update { it.copy(updaterState = updaterState) }
            }
        }
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { inAppUpdater.installedVersion() }
            stateHolder.update {
                it.copy(
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                )
            }
        }
        // Incoming-SMS toggle mirrors the interception flag (single source of truth, also written by
        // the agent tool `auto_number_chat_toggle`, so the UI never drifts from it).
        viewModelScope.launch {
            watchdogConfigStore.interceptionEnabled.collect { enabled ->
                stateHolder.update { it.copy(incomingSmsEnabled = enabled) }
            }
        }
        // Floating-assistant toggle mirrors the persisted flag (single source of truth, also written
        // by the service itself when the user long-presses the bubble to close it).
        viewModelScope.launch {
            overlayAssistantStore.enabled.collect { enabled ->
                stateHolder.update { it.copy(overlayAssistantEnabled = enabled) }
            }
        }
        // Communications («Связи»): mirror every persisted field so the UI never drifts from the
        // store (single source of truth — background workers read the same flags).
        viewModelScope.launch {
            communicationsConfigStore.mode.collect { value ->
                stateHolder.update { it.copy(communicationsMode = value) }
            }
        }
        viewModelScope.launch {
            communicationsConfigStore.keepAudioDays.collect { value ->
                stateHolder.update { it.copy(communicationsKeepAudioDays = value) }
            }
        }
        viewModelScope.launch {
            communicationsConfigStore.audioCacheLimitMb.collect { value ->
                stateHolder.update { it.copy(communicationsAudioCacheLimitMb = value) }
            }
        }
        viewModelScope.launch {
            communicationsConfigStore.maxProcessMinutesPerDay.collect { value ->
                stateHolder.update { it.copy(communicationsMaxProcessMinutesPerDay = value) }
            }
        }
        viewModelScope.launch {
            communicationsConfigStore.whisperModelId.collect { value ->
                stateHolder.update { it.copy(communicationsWhisperModelId = value) }
            }
        }
        viewModelScope.launch {
            communicationsConfigStore.whisperLanguage.collect { value ->
                stateHolder.update { it.copy(communicationsWhisperLanguage = value) }
            }
        }
        viewModelScope.launch {
            communicationsConfigStore.sendTranscriptsToCloud.collect { value ->
                stateHolder.update { it.copy(communicationsSendTranscriptsToCloud = value) }
            }
        }
        viewModelScope.launch {
            communicationsConfigStore.diarizationEnabled.collect { value ->
                stateHolder.update { it.copy(communicationsDiarizationEnabled = value) }
            }
        }
        // Speech-model download: mirror the store's live state and remember which whisper models
        // are already on disk, so the section can offer Download vs. Delete honestly.
        viewModelScope.launch {
            speechModelStore.state.collect { download ->
                stateHolder.update { state ->
                    val ids = if (download is SpeechModelDownloadState.Done) {
                        state.communicationsDownloadedModelIds + download.modelId
                    } else {
                        state.communicationsDownloadedModelIds
                    }
                    state.copy(communicationsDownload = download, communicationsDownloadedModelIds = ids)
                }
            }
        }
        viewModelScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                SpeechModel.entries
                    .filter { it.kind == SpeechModelKind.WHISPER && speechModelStore.isDownloaded(it) }
                    .map { it.id }
                    .toSet()
            }
            stateHolder.update { it.copy(communicationsDownloadedModelIds = downloaded) }
        }
    }

    // --- LLM connection / catalog / form ----------------------------------------------------

    fun onEndpointChange(value: String) = llm.onEndpointChange(value)

    fun onModelChange(value: String) = llm.onModelChange(value)

    /** Optional manual context override; blank keeps the auto-derived value. */
    fun onManualContextChange(value: String) = llm.onManualContextChange(value)

    /** Selecting a model from the dropdown fills the model field and re-derives its context. */
    fun onSelectModel(value: String) = llm.onSelectModel(value)

    fun refreshModels() = llm.refreshModels()

    fun onApiKeyChange(value: String) = llm.onApiKeyChange(value)

    /** Optional display name of the profile currently loaded into the form. */
    fun onProviderNameChange(value: String) = llm.onProviderNameChange(value)

    /** Loads a saved profile into the form and makes it the single active provider. */
    fun selectProvider(id: Long) = llm.selectProvider(id)

    /** Starts a blank NEW profile draft (the active profile keeps serving the chat). */
    fun startNewProvider() = llm.startNewProvider()

    fun requestDeleteProvider(id: Long) = llm.requestDeleteProvider(id)

    fun cancelDeleteProvider() = llm.cancelDeleteProvider()

    fun confirmDeleteProvider() = llm.confirmDeleteProvider()

    /**
     * Explicit key removal — the only path that clears the stored key. Blanking the field and
     * pressing «Сохранить» now means "keep the existing key", so deletion is asked for separately
     * (see the «Удалить ключ» control on [SettingsScreen]).
     */
    fun clearApiKey() = llm.clearApiKey()

    /** Auth style is persisted by [save]; changing it invalidates the catalog fetched with the old scheme. */
    fun onAuthStyleChange(style: LlmAuthStyle) = llm.onAuthStyleChange(style)

    fun onAuthHeaderNameChange(value: String) = llm.onAuthHeaderNameChange(value)

    fun onAuthQueryParamChange(value: String) = llm.onAuthQueryParamChange(value)

    /**
     * Локальный переключатель семантического поиска: значение коммитится кнопкой «Сохранить»
     * (см. [save]). Само включение не запускает индексацию — это сделает фоновый воркер.
     */
    fun onSemanticSearchEnabledChange(value: Boolean) = llm.onSemanticSearchEnabledChange(value)

    fun onReasoningEffortChange(value: String) = llm.onReasoningEffortChange(value)

    fun save() = llm.save()

    // --- Language (per-app locale) ----------------------------------------------------------

    /**
     * Смена языка интерфейса (per-app locale): сохраняет выбор и применяет локаль
     * (`AppCompatDelegate.setApplicationLocales`, активность пересоздаётся). Облачные настройки,
     * чаты и память НЕ трогаются.
     */
    fun onLanguageChange(tag: String) {
        val normalized = AppLanguage.normalize(tag)
        if (normalized == stateHolder.current.language) return
        stateHolder.update { it.copy(language = normalized) }
        viewModelScope.launch {
            runCatching { settingsStore.updateLanguage(normalized) }
                .onFailure { e ->
                    stateHolder.update {
                        it.copy(error = e.message ?: "Не удалось сохранить язык интерфейса")
                    }
                }
        }
        AppLocale.apply(normalized)
    }

    // --- Incoming SMS / overlay -------------------------------------------------------------

    /**
     * Тумблер «Обрабатывать входящие SMS»: пишет ровно тот флаг перехвата, который читает
     * `SmsReceiver` ([WatchdogConfigStore.isInterceptionEnabled]). Значение синхронно отражается
     * в UI через сбор [WatchdogConfigStore.interceptionEnabled] в `init`.
     */
    fun onIncomingSmsEnabledChange(value: Boolean) {
        watchdogConfigStore.setInterceptionEnabled(value)
    }

    /**
     * Тумблер «Плавающий помощник». Решение принимает чистый `OverlayAssistantPolicy`:
     * - выключение → сброс флага и остановка foreground-сервиса;
     * - включение при выданном `SYSTEM_ALERT_WINDOW` → флаг + запуск сервиса;
     * - включение без разрешения → флаг ОСТАЁТСЯ выключенным, показывается честное сообщение и
     *   запрашивается системный экран `ACTION_MANAGE_OVERLAY_PERMISSION` (one-shot через
     *   [SettingsUiState.overlayPermissionPromptNonce]). Фейкового «включено» не бывает.
     */
    fun onOverlayAssistantEnabledChange(value: Boolean) = overlay.onOverlayAssistantEnabledChange(value)

    /** The screen has opened the system overlay screen; clear the one-shot request. */
    fun onOverlayPermissionPromptShown() = overlay.onOverlayPermissionPromptShown()

    /**
     * Re-reads the OS grant. Called when the settings screen resumes, so a permission the user just
     * granted in system Settings is reflected without needing a second toggle flip.
     */
    fun refreshOverlayPermission() = overlay.refreshOverlayPermission()

    // --- Communications (Связи) --------------------------------------------------------------

    fun onCommunicationsModeChange(value: CommunicationsMode) =
        communicationsConfigStore.setMode(value)

    fun onCommunicationsKeepAudioDaysChange(value: Int) =
        communicationsConfigStore.setKeepAudioDays(value)

    fun onCommunicationsAudioCacheLimitMbChange(value: Int) =
        communicationsConfigStore.setAudioCacheLimitMb(value)

    fun onCommunicationsMaxProcessMinutesPerDayChange(value: Int) =
        communicationsConfigStore.setMaxProcessMinutesPerDay(value)

    fun onCommunicationsWhisperModelChange(id: String) =
        communicationsConfigStore.setWhisperModelId(id)

    fun onCommunicationsSendTranscriptsToCloudChange(value: Boolean) =
        communicationsConfigStore.setSendTranscriptsToCloud(value)

    /** Enqueues the selected whisper model's download (single-flight per model in the worker). */
    fun onCommunicationsDownloadWhisperModel() {
        val model = SpeechModel.fromId(stateHolder.current.communicationsWhisperModelId)
            ?: SpeechModel.DEFAULT
        speechModelDownloadScheduler.enqueue(model)
    }

    /** Cancels an in-flight speech-model download; the partial file is kept for a later resume. */
    fun onCommunicationsCancelWhisperDownload() {
        val model = SpeechModel.fromId(stateHolder.current.communicationsWhisperModelId) ?: return
        speechModelDownloadScheduler.cancel(model)
    }

    /** Cancels any in-flight download of the selected model, then deletes its files from disk. */
    fun onCommunicationsDeleteWhisperModel() {
        val model = SpeechModel.fromId(stateHolder.current.communicationsWhisperModelId) ?: return
        speechModelDownloadScheduler.cancel(model)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { speechModelStore.delete(model) }
            stateHolder.update {
                it.copy(
                    communicationsDownloadedModelIds =
                        it.communicationsDownloadedModelIds - model.id,
                )
            }
        }
    }

    // --- Style ------------------------------------------------------------------------------

    /** Switches how (and whether) the assistant mirrors the user's manner. */
    fun onStyleModeChange(mode: StyleMode) {
        stateHolder.update { it.copy(styleMode = mode) }
        viewModelScope.launch { styleProfileStore.setMode(mode) }
    }

    /** Enables/disables mirroring mild swearing without losing the rest of the style. */
    fun onStyleProfanityChange(enabled: Boolean) {
        stateHolder.update { it.copy(styleProfanityEnabled = enabled) }
        viewModelScope.launch { styleProfileStore.setProfanityEnabled(enabled) }
    }

    /** Edits the manual style card, capped to the store's hard limit. */
    fun onStyleManualCardChange(text: String) {
        val capped = text.take(200)
        stateHolder.update { it.copy(styleManualCard = capped, styleManualCardDirty = true) }
    }

    /** Persists the manual card. An overlong card is already prevented by the input cap. */
    fun onStyleManualCardSave() {
        val text = stateHolder.current.styleManualCard
        viewModelScope.launch {
            styleProfileStore.setManualCard(text)
            stateHolder.update { it.copy(styleManualCardDirty = false) }
        }
    }

    /** Forgets the learned profile (mode and the profanity preference are preserved). */
    fun onStyleForget() {
        viewModelScope.launch {
            styleProfileStore.clear()
            stateHolder.update { it.copy(styleManualCardDirty = false) }
        }
    }

    // --- Reset ------------------------------------------------------------------------------

    fun confirmReset() = reset.confirmReset()

    fun cancelReset() = reset.cancelReset()

    fun resetAllData() = reset.resetAllData()

    // --- Updater ----------------------------------------------------------------------------

    /** Manual update check (`force = true`); failures surface via [SettingsUiState.updateStatusText]. */
    fun checkForUpdates() = updater.checkForUpdates()

    fun downloadUpdate() = updater.downloadUpdate()

    fun installUpdate() = updater.installUpdate()

    fun requestUnknownSources() = updater.requestUnknownSources()

    /** Dismisses the current updater dialog and returns the manager to `UpdateState.Idle`. */
    fun dismissUpdate() = updater.dismissUpdate()

    companion object {
        /** Compression budget as a percentage of the discovered model context. */
        const val CONTEXT_BUDGET_PERCENT = LlmSettingsController.CONTEXT_BUDGET_PERCENT

        /**
         * Honest explanation shown when the overlay grant is missing: nothing is enabled, the user is
         * sent to the system list and told to come back.
         */
        const val OVERLAY_PERMISSION_MESSAGE = OverlayAssistantController.OVERLAY_PERMISSION_MESSAGE
    }
}
