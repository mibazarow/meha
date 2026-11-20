package com.andharness.ru.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.data.llm.ModelsCatalogClient
import com.andharness.ru.domain.common.Hashing
import com.andharness.ru.domain.settings.LlmSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsStore: LlmSettingsStore,
    private val modelsCatalogClient: ModelsCatalogClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** Единственный in-flight запрос каталога: новый ввод отменяет предыдущий (см. [scheduleFetch]). */
    private var fetchJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.isConfigured().collect { configured ->
                _uiState.update { it.copy(isConfigured = configured) }
            }
        }
    }

    fun onEndpointChange(value: String) {
        // Правка эндпоинта относится к старому адресу: отменяем запрос и сбрасываем его результат,
        // чтобы устаревшая загрузка не показала чужой каталог. Затем — дебаунс-загрузка, если уже
        // есть ключ и адрес похож на URL (см. [scheduleFetch]).
        fetchJob?.cancel()
        _uiState.update {
            it.copy(
                endpoint = value,
                error = null,
                models = emptyList(),
                modelsLoading = false,
                modelsFetched = false,
                modelsError = null,
            )
        }
        scheduleFetch(value)
    }

    fun onModelChange(value: String) {
        _uiState.update { it.copy(model = value, error = null) }
    }

    /** Выбор из выпадающего списка заполняет всё ещё редактируемое поле «Имя модели». */
    fun onSelectModel(value: String) = onModelChange(value)

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKey = value, error = null) }
        // Ввод ключа — триггер загрузки каталога (дебаунс, единственный in-flight запрос).
        if (value.isNotBlank()) {
            scheduleFetch(_uiState.value.endpoint)
        } else {
            fetchJob?.cancel()
            _uiState.update { it.copy(modelsLoading = false) }
        }
    }

    /**
     * Явное обновление каталога («Обновить список»): без дебаунса и с обходом кэша клиента.
     * Ключ может быть пустым — OpenAI-совместимые серверы работают без авторизации.
     */
    fun refreshOnboardingModels() {
        fetchJob?.cancel()
        val endpoint = _uiState.value.endpoint
        if (endpoint.isBlank()) {
            _uiState.update { it.copy(modelsError = "Не задан адрес эндпоинта.") }
            return
        }
        fetchJob = viewModelScope.launch { loadModels(endpoint, force = true) }
    }

    fun saveAndContinue(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.endpoint.isBlank()) {
            _uiState.update { it.copy(error = "Укажите адрес эндпоинта") }
            return
        }
        // API-ключ и модель необязательны: OpenAI-совместимые серверы могут работать
        // без ключа и с моделью по умолчанию.

        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            settingsStore.setApiKey(state.apiKey)
            settingsStore.updateEndpoint(state.endpoint)
            settingsStore.updateModel(state.model)
            _uiState.update { it.copy(isSaving = false) }
            onSuccess()
        }
    }

    /**
     * Дебаунс-загрузка каталога после ввода ключа/адреса. Держит единственный in-flight запрос:
     * предыдущий [fetchJob] отменяется. Загружаем только когда адрес уже похож на URL и ключ
     * непустой, иначе набор текста спамил бы провайдера.
     *
     * Отмена корутины: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-job/cancel.html
     * Задержка-дебаунс: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/delay.html
     */
    private fun scheduleFetch(endpoint: String) {
        fetchJob?.cancel()
        if (!OnboardingModels.shouldAutoLoad(endpoint, _uiState.value.apiKey)) return
        fetchJob = viewModelScope.launch {
            delay(ENDPOINT_DEBOUNCE_MILLIS)
            loadModels(endpoint, force = false)
        }
    }

    /**
     * Загружает каталог через общий [ModelsCatalogClient] (та же схема авторизации, что и в
     * чате: клиент сам берёт `LlmSettingsStore.getAuthConfig()`, поэтому «Способ авторизации»
     * настраивается в Настройках). Честные 401/403 приходят из клиента как есть.
     *
     * Поздний ответ отбрасывается, если эндпоинт или отпечаток ключа уже изменились, — иначе
     * смена адреса/ключа во время запроса показала бы устаревший каталог.
     */
    private suspend fun loadModels(endpoint: String, force: Boolean) {
        val target = OnboardingModels.normalizeEndpoint(endpoint)
        if (target.isBlank()) return
        _uiState.update { it.copy(modelsLoading = true, modelsError = null) }
        val apiKey = _uiState.value.apiKey
        val requestFingerprint = keyFingerprint(apiKey)
        val catalog = modelsCatalogClient.fetch(target, apiKey, force)
        if (!OnboardingModels.responseStillRelevant(
                requestEndpoint = target,
                requestKeyFingerprint = requestFingerprint,
                currentEndpoint = _uiState.value.endpoint,
                currentKey = _uiState.value.apiKey,
            )
        ) {
            return
        }
        _uiState.update {
            it.copy(
                models = catalog.models,
                modelsLoading = false,
                modelsFetched = true,
                modelsError = catalog.error,
            )
        }
    }

    /**
     * Короткий необратимый отпечаток ключа (SHA-256, hex, усечённый) — общий с
     * [ModelsCatalogClient] и аудит-цепочкой. Сам ключ не хранится и не логируется.
     */
    private fun keyFingerprint(apiKey: String): String = Hashing.keyFingerprint(apiKey)

    companion object {
        /** Дебаунс ввода эндпоинта/ключа, как в Настройках (`SettingsViewModel`). */
        private const val ENDPOINT_DEBOUNCE_MILLIS = 800L
    }
}
