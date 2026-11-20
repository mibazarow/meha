package com.andharness.ru.domain.settings

import com.andharness.ru.domain.locale.AppLanguage

data class LlmSettings(
    val endpoint: String,
    val model: String,
    /**
     * Id of the ACTIVE saved provider profile (see [LlmProvider]); `null` when no profiles exist
     * yet (legacy single config or a fresh install before onboarding saved one). [endpoint]/[model]
     * above always mirror the active profile, so consumers that only need the effective
     * configuration do not have to join the provider list.
     */
    val activeProviderId: Long? = null,
    val contextCompressionTokens: Long = DEFAULT_CONTEXT_COMPRESSION_TOKENS,
    val reasoningEffort: String = DEFAULT_REASONING_EFFORT,
    /**
     * Семантический поиск по долговременной памяти на встроенной модели эмбеддингов
     * (rubert-tiny-turbo-int8, ONNX Runtime, полностью офлайн).
     *
     * Включён по умолчанию: recall дополняется векторным поиском, а встроенная модель
     * ([com.andharness.ru.data.embedding.LocalEmbedder]) прогревается в фоне при старте. Векторы
     * запроса и памяти считаются на устройстве — это расходует процессор и батарею, поэтому
     * пользователь может выключить переключатель в настройках. Отдельного идентификатора модели
     * в настройках нет: используется только встроенная.
     */
    val semanticSearchEnabled: Boolean = true,
    /**
     * Язык интерфейса приложения (per-app locale): "ru" | "en" ([AppLanguage]). По умолчанию —
     * русский. Значение применяется через `AppCompatDelegate.setApplicationLocales` и переживает
     * перезапуск (фреймворк на API 33+, AppCompat `autoStoreLocales` на API ≤ 32).
     */
    val language: String = AppLanguage.DEFAULT
) {
    companion object {
        const val DEFAULT_CONTEXT_COMPRESSION_TOKENS: Long = 180000

        /** Reasoning/thinking effort: "off" | "low" | "medium" | "high". */
        const val DEFAULT_REASONING_EFFORT: String = "off"

        val REASONING_EFFORTS: List<String> = listOf("off", "low", "medium", "high")
    }
}
