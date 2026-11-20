package com.andharness.ru.ui.settings

import android.util.Log
import com.andharness.ru.data.llm.ModelsCatalogClient
import com.andharness.ru.data.prefs.ModelContextPreferences
import com.andharness.ru.domain.common.Hashing
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.llm.LlmAuthStyle
import com.andharness.ru.domain.llm.ModelsCatalog
import com.andharness.ru.domain.llm.normalizeEndpointBase
import com.andharness.ru.domain.settings.CompressionBudgetPolicy
import com.andharness.ru.domain.settings.ContextLimitPolicy
import com.andharness.ru.domain.settings.LlmProvider
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles the cloud-LLM half of the settings screen: endpoint, API key, auth scheme, model catalog,
 * model name and the AUTO-derived context (provider maximum from `/models` or the offline known-model
 * table, else the fixed default — see [ContextLimitPolicy]), plus the reasoning/semantic-search
 * fields. Owns the single in-flight catalog [fetchJob] and the last fetched [lastCatalog], and
 * persists the form via [save]. State is written into the shared [SettingsStateHolder]; the ViewModel
 * keeps the thin, source-compatible delegation surface.
 */
class LlmSettingsController(
    private val state: SettingsStateHolder,
    private val scope: CoroutineScope,
    private val settingsStore: LlmSettingsStore,
    private val modelsCatalogClient: ModelsCatalogClient,
    private val modelContextPreferences: ModelContextPreferences,
) {

    private var fetchJob: Job? = null

    /** Pending reset of the transient [SettingsUiState.saved] flag; replaced on every save. */
    private var saveResetJob: Job? = null

    /** Last catalog fetched for the current endpoint; kept for context resolution. */
    private var lastCatalog: ModelsCatalog? = null

    fun onEndpointChange(value: String) {
        // The endpoint is only a local edit until a key is entered or «Обновить список» is
        // pressed, so nothing is fetched here. Any in-flight request belongs to the old endpoint
        // and is cancelled/discarded, so the stale spinner must be cleared too.
        fetchJob?.cancel()
        val max = maxContextFor(state.current.model)
        val manual = state.current.manualContextTokens.trim().toLongOrNull()
        state.update {
            it.copy(
                endpoint = value,
                error = null,
                providerFormDirty = true,
                models = emptyList(),
                modelsLoading = false,
                modelsFetched = false,
                modelsError = null,
                modelContextTokens = ContextLimitPolicy.effectiveContext(manual, max),
                modelMaxContextTokens = max,
            )
        }
        lastCatalog = null
    }

    fun onModelChange(value: String) {
        val max = maxContextFor(value)
        val manual = state.current.manualContextTokens.trim().toLongOrNull()
        state.update {
            it.copy(
                model = value,
                error = null,
                providerFormDirty = true,
                modelContextTokens = ContextLimitPolicy.effectiveContext(manual, max),
                modelMaxContextTokens = max,
            )
        }
    }

    /** Optional display name of the profile being edited. */
    fun onProviderNameChange(value: String) {
        state.update { it.copy(providerName = value, error = null, providerFormDirty = true) }
    }

    /** Optional manual context override; blank keeps the auto-derived value. Digits only. */
    fun onManualContextChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(9)
        val manual = digits.toLongOrNull()
        state.update {
            it.copy(
                manualContextTokens = digits,
                error = null,
                modelContextTokens =
                    ContextLimitPolicy.effectiveContext(manual, it.modelMaxContextTokens),
            )
        }
    }

    /** Selecting a model from the dropdown fills the model field and re-derives its context. */
    fun onSelectModel(value: String) = onModelChange(value)

    fun refreshModels() {
        fetchJob?.cancel()
        val endpoint = state.current.endpoint
        if (endpoint.isBlank()) {
            state.update { it.copy(modelsError = "Не задан адрес эндпоинта.") }
            return
        }
        fetchJob = scope.launch { loadModels(endpoint, force = true) }
    }

    fun onApiKeyChange(value: String) {
        state.update { it.copy(apiKey = value, error = null, providerFormDirty = true) }
        // Entering a key is the trigger that loads the catalog (debounced, single in-flight job).
        if (value.isNotBlank()) {
            scheduleFetch(state.current.endpoint)
        } else {
            fetchJob?.cancel()
            state.update { it.copy(modelsLoading = false) }
        }
    }

    /**
     * Explicit key removal — the only path that clears the stored key. Blanking the field and
     * pressing «Сохранить» now means "keep the existing key", so deletion is asked for separately
     * (see the «Удалить ключ» control on `SettingsScreen`).
     */
    fun clearApiKey() {
        // Credentials changed: cancel any in-flight catalog request and drop its stale result.
        fetchJob?.cancel()
        lastCatalog = null
        state.update {
            it.copy(
                apiKey = "",
                error = null,
                models = emptyList(),
                modelsLoading = false,
                modelsFetched = false,
                modelsError = null,
            )
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { settingsStore.clearApiKey() }
            } catch (e: Exception) {
                state.update { it.copy(error = e.message ?: "Не удалось удалить API-ключ") }
            }
        }
    }

    /** Auth style is persisted by [save]; changing it invalidates the catalog fetched with the old scheme. */
    fun onAuthStyleChange(style: LlmAuthStyle) {
        state.update { it.copy(authStyle = style, error = null, providerFormDirty = true) }
        onAuthConfigChanged()
    }

    fun onAuthHeaderNameChange(value: String) {
        state.update { it.copy(authHeaderName = value, error = null, providerFormDirty = true) }
        onAuthConfigChanged()
    }

    fun onAuthQueryParamChange(value: String) {
        state.update { it.copy(authQueryParam = value, error = null, providerFormDirty = true) }
        onAuthConfigChanged()
    }

    /**
     * Credentials changed: cancel any in-flight catalog request, drop its stale result and, when an
     * endpoint and a key (typed or stored) are present, reload the catalog with the new scheme. The
     * reload is debounced and kept on the single [fetchJob], mirroring [onApiKeyChange].
     */
    private fun onAuthConfigChanged() {
        fetchJob?.cancel()
        lastCatalog = null
        state.update {
            it.copy(
                models = emptyList(),
                modelsLoading = false,
                modelsFetched = false,
                modelsError = null,
            )
        }
        val endpoint = state.current.endpoint
        if (endpoint.isBlank()) return
        fetchJob = scope.launch {
            val key = state.current.apiKey.ifBlank {
                withContext(Dispatchers.IO) {
                    runCatching { settingsStore.getApiKey() }.onFailure { Log.w(TAG, "read stored API key failed", it) }.getOrNull().orEmpty()
                }
            }
            if (key.isBlank()) return@launch
            delay(ENDPOINT_DEBOUNCE_MILLIS)
            loadModels(endpoint, force = false)
        }
    }

    /**
     * Локальный переключатель семантического поиска: значение коммитится кнопкой «Сохранить»
     * (см. [save]). Само включение не запускает индексацию — это сделает фоновый воркер.
     */
    fun onSemanticSearchEnabledChange(value: Boolean) {
        state.update { it.copy(semanticSearchEnabled = value, error = null) }
    }

    fun onReasoningEffortChange(value: String) {
        state.update { it.copy(reasoningEffort = value, error = null) }
    }

    /**
     * Loads a saved profile into the form and makes it the single ACTIVE provider at once: the
     * store mirrors its endpoint/model/auth/key into the effective configuration, so the chat
     * starts using it immediately (no separate "activate" step). The API key is seeded
     * asynchronously and only if the form still shows the same profile when it arrives.
     */
    fun selectProvider(id: Long) {
        val provider = state.current.providers.firstOrNull { it.id == id } ?: return
        fetchJob?.cancel()
        lastCatalog = null
        val max = maxContextFor(provider.model)
        val manual = state.current.manualContextTokens.trim().toLongOrNull()
        state.update {
            it.copy(
                providerName = provider.name,
                endpoint = provider.endpoint,
                model = provider.model,
                apiKey = "",
                authStyle = provider.auth.style,
                authHeaderName = provider.auth.headerName,
                authQueryParam = provider.auth.queryParam,
                editingProviderId = id,
                activeProviderId = id,
                providerDraft = false,
                providerFormDirty = false,
                error = null,
                models = emptyList(),
                modelsLoading = false,
                modelsFetched = false,
                modelsError = null,
                modelContextTokens = ContextLimitPolicy.effectiveContext(manual, max),
                modelMaxContextTokens = max,
            )
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { settingsStore.activateProvider(id) }
                val key = withContext(Dispatchers.IO) { settingsStore.providerApiKey(id) }.orEmpty()
                if (state.current.editingProviderId == id) {
                    state.update { it.copy(apiKey = key) }
                }
            } catch (e: Exception) {
                state.update { it.copy(error = e.message ?: "Не удалось выбрать провайдера") }
            }
        }
    }

    /** Starts a NEW profile draft; the currently active profile keeps serving the chat meanwhile. */
    fun startNewProvider() {
        fetchJob?.cancel()
        lastCatalog = null
        state.update {
            it.copy(
                providerName = "",
                endpoint = "",
                model = "",
                apiKey = "",
                authStyle = LlmAuthStyle.BEARER,
                authHeaderName = "",
                authQueryParam = "",
                editingProviderId = null,
                providerDraft = true,
                providerFormDirty = false,
                error = null,
                models = emptyList(),
                modelsLoading = false,
                modelsFetched = false,
                modelsError = null,
                modelContextTokens = null,
                modelMaxContextTokens = null,
            )
        }
    }

    fun requestDeleteProvider(id: Long) {
        state.update { it.copy(providerDeleteCandidateId = id) }
    }

    fun cancelDeleteProvider() {
        state.update { it.copy(providerDeleteCandidateId = null) }
    }

    /**
     * Deletes the candidate profile (and its key). If it was being edited, the form switches to
     * the next active profile, or to a blank draft when the last profile is gone.
     */
    fun confirmDeleteProvider() {
        val id = state.current.providerDeleteCandidateId ?: return
        state.update { it.copy(providerDeleteCandidateId = null) }
        val wasEditing = state.current.editingProviderId == id
        scope.launch {
            try {
                withContext(Dispatchers.IO) { settingsStore.deleteProvider(id) }
                if (wasEditing) {
                    val settings = withContext(Dispatchers.IO) { settingsStore.config.first() }
                    val nextId = settings.activeProviderId
                    if (nextId != null) selectProvider(nextId) else startNewProvider()
                }
            } catch (e: Exception) {
                state.update { it.copy(error = e.message ?: "Не удалось удалить провайдера") }
            }
        }
    }

    fun save() {
        val snapshot = state.current
        if (snapshot.endpoint.isBlank()) {
            state.update { it.copy(error = "Укажите адрес эндпоинта.") }
            return
        }

        // The model's maximum context, when the endpoint/known-model table reports one; the
        // effective context is auto-derived from it (provider max, else the fixed default).
        val maxContext = maxContextFor(snapshot.model)
        val effectiveContext = ContextLimitPolicy.effectiveContext(
            snapshot.manualContextTokens.trim().toLongOrNull(),
            maxContext,
        )

        // The compression budget is ALWAYS 80 % of the effective model context (there is no
        // user-facing budget setting); it is re-derived on EVERY save, so changing the context
        // updates the budget.
        val budget = CompressionBudgetPolicy.derive(effectiveContext)

        // A new save restarts the transient indication: drop any pending reset and hide the flag.
        saveResetJob?.cancel()
        state.update { it.copy(isLoading = true, saved = false, error = null) }
        scope.launch {
            try {
                // Everything that defines "which provider we talk to" goes through the profile
                // store: it inserts a NEW profile or updates the edited one and, because the save
                // button is an explicit user choice, makes it the single active one.
                val provider = LlmProvider(
                    id = snapshot.editingProviderId ?: LlmProvider.UNSAVED_ID,
                    name = snapshot.providerName,
                    endpoint = snapshot.endpoint,
                    model = snapshot.model,
                    auth = LlmAuthConfig(
                        style = snapshot.authStyle,
                        headerName = snapshot.authHeaderName.trim(),
                        queryParam = snapshot.authQueryParam.trim(),
                    ),
                )
                val savedId = settingsStore.saveProvider(
                    provider = provider,
                    // A blank field means "keep the stored key": the field is seeded
                    // asynchronously and may legitimately be empty, so Save must never wipe the
                    // (unrecoverable) key. Removal is an explicit action only — see [clearApiKey].
                    apiKey = snapshot.apiKey.takeIf { it.isNotBlank() },
                    activate = true,
                )
                // The key is now persisted (or kept): (re)load the catalog with it, if possible.
                if (snapshot.apiKey.isNotBlank() && snapshot.endpoint.isNotBlank()) {
                    scheduleFetch(snapshot.endpoint)
                }
                // Выключенный переключатель честно оставляет recall лексическим (BM25).
                settingsStore.updateSemanticSearchEnabled(snapshot.semanticSearchEnabled)
                settingsStore.updateReasoningEffort(snapshot.reasoningEffort)
                modelContextPreferences.setModelContextTokens(effectiveContext)
                // The budget is ALWAYS re-derived (80 %) from the CURRENT model context and stored
                // in the existing `settings.contextCompressionTokens` column.
                budget?.let { settingsStore.updateContextCompressionTokens(it) }
                state.update {
                    it.copy(
                        isLoading = false,
                        saved = true,
                        editingProviderId = savedId,
                        activeProviderId = savedId,
                        providerDraft = false,
                        providerFormDirty = false,
                        modelContextTokens = effectiveContext,
                        modelMaxContextTokens = maxContext,
                    )
                }
                // The success indication is transient: it lives only in the button label and clears
                // itself after a short window, so no standalone status row is needed.
                saveResetJob?.cancel()
                saveResetJob = scope.launch {
                    delay(SAVE_INDICATION_MILLIS)
                    state.update { it.copy(saved = false) }
                }
            } catch (e: Exception) {
                state.update {
                    it.copy(isLoading = false, error = e.message ?: "Не удалось сохранить настройки")
                }
            }
        }
    }

    /** Drops the cached catalog (used when all settings are reset). */
    fun clearCatalog() {
        lastCatalog = null
    }

    /**
     * Debounced (re)load triggered after the API key is entered or saved. Keeps a single in-flight
     * request: any previous [fetchJob] is cancelled first. Only http(s) endpoints are attempted so
     * a half-typed address does not spam requests.
     */
    private fun scheduleFetch(endpoint: String) {
        fetchJob?.cancel()
        if (!endpoint.trim().startsWith("http")) return
        fetchJob = scope.launch {
            delay(ENDPOINT_DEBOUNCE_MILLIS)
            loadModels(endpoint, force = false)
        }
    }

    /** Used by the ViewModel's init to auto-load the catalog once on open. */
    suspend fun loadModels(endpoint: String, force: Boolean) {
        val target = normalizeEndpointBase(endpoint)
        if (target.isBlank()) return
        state.update { it.copy(modelsLoading = true, modelsError = null) }
        val apiKey = state.current.apiKey.ifBlank {
            runCatching { settingsStore.getApiKey() }.onFailure { Log.w(TAG, "read stored API key failed", it) }.getOrNull().orEmpty()
        }
        val requestFingerprint = keyFingerprint(apiKey)
        val catalog = modelsCatalogClient.fetch(target, apiKey, force)
        // A late response is discarded unless BOTH the endpoint and the key still match what the
        // request was made with, so switching the key mid-flight cannot show a stale catalog.
        val currentKey = state.current.apiKey.ifBlank {
            runCatching { settingsStore.getApiKey() }.onFailure { Log.w(TAG, "read stored API key failed", it) }.getOrNull().orEmpty()
        }
        if (normalizeEndpointBase(state.current.endpoint) != target ||
            keyFingerprint(currentKey) != requestFingerprint
        ) {
            return
        }
        lastCatalog = catalog
        val max = maxContextFor(state.current.model)
        val manual = state.current.manualContextTokens.trim().toLongOrNull()
        state.update {
            it.copy(
                models = catalog.models,
                modelsLoading = false,
                modelsFetched = true,
                modelsError = catalog.error,
                modelContextTokens = ContextLimitPolicy.effectiveContext(manual, max),
                modelMaxContextTokens = max,
            )
        }
    }

    /**
     * Short non-reversible fingerprint of [apiKey] (SHA-256, hex, truncated), shared with the
     * audit chain and [ModelsCatalogClient]. The raw key is never stored or logged; a blank key
     * maps to one constant so keyless local servers compare equal.
     */
    private fun keyFingerprint(apiKey: String): String = Hashing.keyFingerprint(apiKey)

    /** Maximum context the model supports (from the provider `/models` catalog); null if unknown. */
    fun maxContextFor(model: String): Long? = resolveContext(model)

    private fun resolveContext(model: String): Long? {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return null
        lastCatalog?.let { catalog ->
            catalog.contextById[trimmed]?.let { return it }
            catalog.endpointContextFallback?.let { return it }
        }
        return null
    }

    companion object {
        /** Compression budget as a percentage of the discovered model context (see the policy). */
        const val CONTEXT_BUDGET_PERCENT = CompressionBudgetPolicy.PERCENT
        private const val ENDPOINT_DEBOUNCE_MILLIS = 800L

        /** How long the transient «Сохранено» button label stays up after a successful save. */
        private const val SAVE_INDICATION_MILLIS = 3500L
        private const val TAG = "Andharness"
    }
}
