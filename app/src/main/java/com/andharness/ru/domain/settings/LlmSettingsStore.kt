package com.andharness.ru.domain.settings

import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.locale.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface LlmSettingsStore {
    val config: Flow<LlmSettings>

    /**
     * Saved provider profiles, oldest first. Empty for stores that do not support profiles (the
     * default implementation), so existing test fakes keep compiling and behaving as before.
     */
    val providers: Flow<List<LlmProvider>>
        get() = flowOf(emptyList())

    suspend fun updateEndpoint(value: String)
    suspend fun updateModel(value: String)
    suspend fun updateReasoningEffort(value: String)

    /**
     * Включает/выключает семантический поиск по памяти (встроенная офлайн-модель эмбеддингов).
     * Реализация по умолчанию ничего не делает, чтобы тестовые фейки стора не ломались.
     */
    suspend fun updateSemanticSearchEnabled(value: Boolean) = Unit

    /** Persists the context-compression budget (existing `settings.contextCompressionTokens`). */
    suspend fun updateContextCompressionTokens(value: Long)

    /**
     * Сохраняет выбранный язык интерфейса ("ru" | "en"). Реализация по умолчанию ничего не делает,
     * чтобы тестовые фейки стора не ломались.
     */
    suspend fun updateLanguage(value: String) = Unit

    /**
     * Текущий сохранённый язык интерфейса (синхронное чтение prefs) — нужен при старте приложения,
     * чтобы применить локаль до первой композиции. По умолчанию — русский.
     */
    fun currentLanguage(): String = AppLanguage.DEFAULT

    /**
     * Выбирал ли пользователь язык интерфейса ЯВНО. Гейтит экран выбора языка ПЕРЕД лицензией:
     * `false` (первый запуск) — экран показывается; после явного выбора в самом экране выбора он
     * становится `true` и больше не показывается (смена языка остаётся доступной в Настройках).
     *
     * Важно: это ВЫДЕЛЕННЫЙ флаг, выставляемый ТОЛЬКО явным выбором в экране выбора
     * ([markLanguageChosen]) — он НЕ выводится из значения/наличия языка интерфейса: тот может
     * быть записан и по умолчанию («ru»), из-за чего экран выбора ошибочно пропускался бы на
     * первом запуске. Реализация по умолчанию — `false`, чтобы тестовые фейки не ломались.
     */
    fun hasChosenLanguage(): Boolean = false

    /**
     * Помечает, что пользователь ЯВНО выбрал язык интерфейса в экране выбора. Вызывается ТОЛЬКО
     * оттуда (и никогда — сменой языка в Настройках), поэтому один раз выставленный флаг остаётся
     * `true` навсегда и экран выбора не возвращается. Реализация по умолчанию ничего не делает,
     * чтобы тестовые фейки не ломались.
     */
    fun markLanguageChosen() = Unit

    suspend fun getApiKey(): String?
    suspend fun setApiKey(key: String)
    suspend fun clearApiKey()

    /** Provider auth scheme (update.md, Приоритет №1): Bearer / custom header / query param / none. */
    suspend fun getAuthConfig(): LlmAuthConfig
    suspend fun setAuthConfig(config: LlmAuthConfig)

    /**
     * Inserts or updates a provider profile and returns its id. A non-blank [apiKey] is stored for
     * that profile in the encrypted preferences; `null`/blank keeps the existing key (blanking the
     * field therefore never wipes a stored secret — explicit removal goes through [clearApiKey]).
     * When [activate] is `true` the profile becomes the single active one.
     *
     * Defaults keep profile-unaware test fakes source-compatible.
     */
    suspend fun saveProvider(
        provider: LlmProvider,
        apiKey: String? = null,
        activate: Boolean = true,
    ): Long = provider.id

    /** Makes an existing profile the single active one (no-op for an unknown id). */
    suspend fun activateProvider(id: Long) = Unit

    /** Deletes a profile and its stored key; if it was active, the next profile takes over. */
    suspend fun deleteProvider(id: Long) = Unit

    /** Stored API key of one profile (used to seed the edit form), `null` when absent. */
    suspend fun providerApiKey(id: Long): String? = null

    /** Restores the settings profile to defaults and clears the API key. */
    suspend fun reset()

    fun isConfigured(): Flow<Boolean>
}
