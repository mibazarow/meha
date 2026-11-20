package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.andharness.ru.domain.llm.LlmAuthStyle

/**
 * Persisted provider profile (non-secret half): name, endpoint, model and auth scheme. The API key
 * for each profile lives ONLY in the encrypted preferences under
 * `llm_api_key_p<id>` — it is never written to this table.
 *
 * The active profile's [endpoint]/[model] are mirrored into the single `settings` row
 * (`SettingsEntity.activeProviderId`), so existing consumers keep a single effective config.
 */
@Entity(tableName = "llm_providers")
data class LlmProviderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String = "",
    val endpoint: String = "",
    val model: String = "",
    val authStyle: String = LlmAuthStyle.BEARER.storageValue,
    val authHeaderName: String = "",
    val authQueryParam: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
