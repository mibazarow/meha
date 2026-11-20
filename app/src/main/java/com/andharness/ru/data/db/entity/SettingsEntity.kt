package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val endpoint: String = "",
    val model: String = "",
    /**
     * Id of the ACTIVE saved provider profile (`llm_providers`); `null` for a legacy install or a
     * fresh one before any profile is saved. [endpoint]/[model] above always mirror this profile.
     */
    val activeProviderId: Long? = null,
    val maxChats: Int = 100,
    val allowAllActions: Boolean = false,
    val applyFileChangesWithoutDiff: Boolean = false,
    val systemPromptAppend: String = "",
    val contextCompressionTokens: Long = com.andharness.ru.domain.settings.LlmSettings.DEFAULT_CONTEXT_COMPRESSION_TOKENS,
    val reasoningEffort: String = com.andharness.ru.domain.settings.LlmSettings.DEFAULT_REASONING_EFFORT,
    val useLocalModel: Boolean = false,
    val localModelPath: String = "",
    val secondaryModel: String = "",
    val routingEnabled: Boolean = false,
    val primaryInputPricePerMillion: Double = 0.0,
    val primaryOutputPricePerMillion: Double = 0.0,
    val secondaryInputPricePerMillion: Double = 0.0,
    val secondaryOutputPricePerMillion: Double = 0.0,
    val privacyMode: String = "SECRETS",
    /**
     * OCR script for the offline recognizer: CYRILLIC (`rus+eng`) or LATIN (`eng`).
     * ML Kit has no Cyrillic model, so the default is CYRILLIC.
     */
    val ocrScript: String = com.andharness.ru.domain.vision.OcrScript.DEFAULT.storageValue,
    /**
     * Модель эмбеддингов для семантического поиска; пусто — семантика выключена.
     * Столбец добавлен миграцией 28 -> 29 как NOT NULL DEFAULT '' и оставлен «спящим»:
     * семантику теперь включает только встроенная офлайн-модель (см. [semanticSearchEnabled]).
     */
    val embeddingModel: String = "",
    /**
     * Семантический поиск по памяти на встроенной офлайн-модели эмбеддингов. Включён по умолчанию:
     * новые установки получают `true`, а существующие строки включает разовая миграция 32 -> 33
     * (столбец добавлен миграцией 29 -> 30 как NOT NULL DEFAULT 0). Пользователь может выключить
     * его переключателем в настройках.
     */
    val semanticSearchEnabled: Boolean = true
)
