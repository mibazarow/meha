package com.andharness.ru.ui.settings

import com.andharness.ru.data.update.UpdateState
import com.andharness.ru.domain.communications.CommunicationsConfig
import com.andharness.ru.domain.communications.CommunicationsMode
import com.andharness.ru.domain.communications.speech.SpeechModelDownloadState
import com.andharness.ru.domain.llm.LlmAuthStyle
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.domain.settings.CompressionBudgetPolicy
import com.andharness.ru.domain.settings.LlmProvider
import com.andharness.ru.domain.style.AddressForm
import com.andharness.ru.domain.style.StyleMode
import com.andharness.ru.domain.style.StyleTraitKey
import com.andharness.ru.domain.update.UpdateManifest
import com.andharness.ru.domain.update.UpdatePolicy

/**
 * Observable state of the whole settings screen. Owned by [SettingsStateHolder] and published by
 * [SettingsViewModel]; the editable fields and the in-app updater state are mirrored here so the
 * screen has a single source of truth.
 */
data class SettingsUiState(
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    /** Saved provider profiles, oldest first (see `LlmProvider`). */
    val providers: List<LlmProvider> = emptyList(),
    /** Id of the single ACTIVE saved profile; `null` while only a draft/legacy config exists. */
    val activeProviderId: Long? = null,
    /** Profile id currently loaded into the form; `null` while composing a NEW profile. */
    val editingProviderId: Long? = null,
    /** `true` while the form is a NEW profile draft not yet saved. */
    val providerDraft: Boolean = false,
    /** Optional display name of the profile being edited. */
    val providerName: String = "",
    /** Profile id waiting for the delete confirmation dialog, if any. */
    val providerDeleteCandidateId: Long? = null,
    /**
     * `true` once the user edited any profile field since the last load/save. While set, persisted
     * config updates do not overwrite the form, so a background emission cannot clobber typing.
     */
    val providerFormDirty: Boolean = false,
    /**
     * Язык интерфейса (per-app locale): "ru" | "en" ([AppLanguage]). Зеркало
     * [com.andharness.ru.domain.settings.LlmSettings.language]; по умолчанию — русский.
     */
    val language: String = AppLanguage.DEFAULT,
    /** How the API key is attached to requests (update.md, Приоритет №1). */
    val authStyle: LlmAuthStyle = LlmAuthStyle.BEARER,
    /** Header name used when [authStyle] is [LlmAuthStyle.HEADER], e.g. `x-api-key`. */
    val authHeaderName: String = "",
    /** Query parameter name used when [authStyle] is [LlmAuthStyle.QUERY], e.g. `api_key`. */
    val authQueryParam: String = "",
    /**
     * Семантический поиск по памяти на встроенной офлайн-модели эмбеддингов.
     * Включён по умолчанию; при выключении recall остаётся чисто лексическим (BM25).
     * `false` ниже — стартовое состояние формы до загрузки сохранённых настроек.
     */
    val semanticSearchEnabled: Boolean = false,
    val reasoningEffort: String = com.andharness.ru.domain.settings.LlmSettings.DEFAULT_REASONING_EFFORT,
    /** Learned communication style: user-selected adaptation mode. */
    val styleMode: StyleMode = StyleMode.AUTO,
    /** Active learned traits, in render priority order (see `StyleActivation`). */
    val styleTraits: List<StyleTraitKey> = emptyList(),
    /** Dominant address form when the ADDRESS trait is active. */
    val styleAddressForm: AddressForm? = null,
    /** Model-produced nuance card; shown for transparency, not editable inline. */
    val styleCard: String? = null,
    /** User-authored card shown in MANUAL mode (editable draft). */
    val styleManualCard: String = "",
    /** True once the user edited the manual card and it must not be clobbered by a store emission. */
    val styleManualCardDirty: Boolean = false,
    /** Whether mild swearing may be mirrored. */
    val styleProfanityEnabled: Boolean = true,
    /** Number of user messages the profile is based on. */
    val styleSamples: Int = 0,
    val isLoading: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val showResetConfirmation: Boolean = false,
    /** Models discovered from `GET {baseUrl}/models` (empty until a successful fetch). */
    val models: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsFetched: Boolean = false,
    val modelsError: String? = null,
    /**
     * Effective context of the selected model: the manual override when set, else the provider
     * maximum, else the fixed default (see [ContextLimitPolicy.effectiveContext]).
     */
    val modelContextTokens: Long? = null,
    /**
     * Maximum context the provider reports for the selected model (from the `/models` payload); null
     * means the provider reported nothing.
     */
    val modelMaxContextTokens: Long? = null,
    /**
     * Optional manual context override typed by the user (digits only). Blank = auto (provider max,
     * else the fixed default). Used when the user is confident the auto-detected window is wrong.
     */
    val manualContextTokens: String = "",
    /** Live lifecycle of the in-app updater (single source: the bound `InAppUpdater`). */
    val updaterState: UpdateState = UpdateState.Idle,
    /** Installed version read from `PackageManager` (BuildConfig is disabled). */
    val installedVersionName: String = "",
    val installedVersionCode: Long = 0L,
    /** Transient updater confirmation, e.g. «Установлена последняя версия». */
    val updateMessage: String? = null,
    val updateMessageIsError: Boolean = false,
    /**
     * Обработка входящих SMS. Совпадает с флагом перехвата ([WatchdogConfigStore]): именно его
     * проверяет `SmsReceiver`, поэтому тумблер делает приёмник «живым». Включён по умолчанию
     * (как и сам флаг): стартовое значение формы берётся напрямую из стора, чтобы UI показывал
     * корректное состояние ещё до первого сбора потока.
     */
    val incomingSmsEnabled: Boolean = true,
    /**
     * «Плавающий помощник» — the floating overlay bubble. Default OFF; the toggle only turns on
     * after the user grants `SYSTEM_ALERT_WINDOW` ([overlayAssistantPermissionGranted]).
     */
    val overlayAssistantEnabled: Boolean = false,
    /** Live `Settings.canDrawOverlays` answer, so the UI can explain the real state honestly. */
    val overlayAssistantPermissionGranted: Boolean = false,
    /** Honest hint shown under the toggle when the OS grant is missing. */
    val overlayPermissionMessage: String? = null,
    /**
     * One-shot request flag: a non-zero value asks the screen to open
     * `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` exactly once (the screen resets it afterwards).
     */
    val overlayPermissionPromptNonce: Long = 0L,
    /**
     * Communications («Связи») mode. Mirrors [CommunicationsConfigStore.mode] — the flag the capture
     * pipeline reads to decide when audio may be recorded. Default [CommunicationsMode.CHARGING].
     */
    val communicationsMode: CommunicationsMode = CommunicationsConfig.DEFAULT_MODE,
    /** Days of source audio to keep before the retention pass may delete it (clamped 1..30). */
    val communicationsKeepAudioDays: Int = CommunicationsConfig.DEFAULT_KEEP_AUDIO_DAYS,
    /** On-device audio cache ceiling in MiB (clamped 50..5000). */
    val communicationsAudioCacheLimitMb: Int = CommunicationsConfig.DEFAULT_AUDIO_CACHE_LIMIT_MB,
    /** Daily wall-clock budget for background transcription, in minutes (clamped 15..1440). */
    val communicationsMaxProcessMinutesPerDay: Int =
        CommunicationsConfig.DEFAULT_MAX_PROCESS_MINUTES_PER_DAY,
    /** Selected whisper model id ([SpeechModel]); base by default. */
    val communicationsWhisperModelId: String = CommunicationsConfig.DEFAULT_WHISPER_MODEL_ID,
    /** Speech language passed to whisper; `auto` for detection. */
    val communicationsWhisperLanguage: String = CommunicationsConfig.DEFAULT_WHISPER_LANGUAGE,
    /**
     * `true` (default) allows transcript TEXT to reach the cloud LLM for summaries. Source audio
     * never leaves the device either way; `false` keeps everything local (search-only).
     */
    val communicationsSendTranscriptsToCloud: Boolean =
        CommunicationsConfig.DEFAULT_SEND_TRANSCRIPTS_TO_CLOUD,
    /** Speaker diarization flag (config-only in this unit; no UI toggle yet). */
    val communicationsDiarizationEnabled: Boolean = CommunicationsConfig.DEFAULT_DIARIZATION_ENABLED,
    /** Live state of the last/current speech-model download (single source: [SpeechModelStore]). */
    val communicationsDownload: SpeechModelDownloadState = SpeechModelDownloadState.NotDownloaded,
    /** Ids of whisper models whose verified file is present on disk (for the download/delete UI). */
    val communicationsDownloadedModelIds: Set<String> = emptySet(),
) {
    /** The update currently on offer, if the state is [UpdateState.Available]. */
    val availableUpdate: UpdateManifest?
        get() = (updaterState as? UpdateState.Available)?.manifest

    /** `true` when the offered update cannot be postponed (manifest flag or min supported build). */
    val isUpdateMandatory: Boolean
        get() {
            val manifest = availableUpdate ?: return false
            return UpdatePolicy.isMandatory(manifest, installedVersionCode)
        }

    /** Status line under the update row: a transient message, else the honest updater error. */
    val updateStatusText: String?
        get() = updateMessage ?: (updaterState as? UpdateState.Error)?.message

    /** `true` when [updateStatusText] describes a failure and should render in the error color. */
    val updateStatusIsError: Boolean
        get() = updateMessageIsError || updaterState is UpdateState.Error

    /**
     * `true` when the provider reported a maximum for the selected model: the read-only context
     * explanation then shows the "provider reported" variant instead of the fixed-default one.
     * Pure predicate so the variant choice is JVM-testable.
     */
    val contextExplanationUsesProviderMax: Boolean
        get() = modelMaxContextTokens != null

    /**
     * The compression budget the chat indicator will show: always 80 % of
     * [modelContextTokens]. Kept in lock-step with what Save persists.
     */
    val effectiveCompressionBudgetTokens: Long?
        get() = CompressionBudgetPolicy.derive(modelContextTokens)
}
