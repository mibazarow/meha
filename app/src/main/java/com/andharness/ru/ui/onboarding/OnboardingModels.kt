package com.andharness.ru.ui.onboarding

import com.andharness.ru.domain.common.Hashing
import com.andharness.ru.domain.llm.normalizeEndpointBase

/**
 * Чистая (без Android) логика облачного шага онбординга: когда грузить каталог и когда поздний
 * ответ ещё актуален. Вынесена отдельно, чтобы правила были покрыты JVM-тестами.
 */
internal object OnboardingModels {

    /** Адрес считается пригодным, только когда он уже похож на http(s)-URL. */
    fun looksLikeUrl(endpoint: String): Boolean = endpoint.trim().startsWith("http")

    /** Идентичность эндпоинта: обрезаем пробелы и завершающий «/», как это делает клиент. */
    fun normalizeEndpoint(endpoint: String): String = normalizeEndpointBase(endpoint)

    /** Автозагрузка возможна только при URL-подобном адресе И непустом ключе. */
    fun shouldAutoLoad(endpoint: String, apiKey: String): Boolean =
        apiKey.isNotBlank() && looksLikeUrl(endpoint)

    /**
     * [true], если ответ, полученный для ([requestEndpoint], [requestKeyFingerprint]), всё ещё
     * относится к текущему состоянию экрана: адрес и отпечаток ключа не изменились.
     */
    fun responseStillRelevant(
        requestEndpoint: String,
        requestKeyFingerprint: String,
        currentEndpoint: String,
        currentKey: String,
    ): Boolean =
        normalizeEndpoint(requestEndpoint) == normalizeEndpoint(currentEndpoint) &&
            requestKeyFingerprint == Hashing.keyFingerprint(currentKey)
}
