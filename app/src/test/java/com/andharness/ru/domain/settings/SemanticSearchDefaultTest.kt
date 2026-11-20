package com.andharness.ru.domain.settings

import com.andharness.ru.data.db.entity.SettingsEntity
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Semantic memory search is ON by default: a fresh install must get the bundled offline embedder
 * without any user action, and the user can still switch it off in Settings. Existing installs are
 * covered by the one-time migration 32 -> 33 (`UPDATE settings SET semanticSearchEnabled = 1`).
 */
class SemanticSearchDefaultTest {

    @Test
    fun settingsEntityDefaultsSemanticSearchOn() {
        assertTrue(SettingsEntity().semanticSearchEnabled)
    }

    @Test
    fun llmSettingsDefaultsSemanticSearchOn() {
        assertTrue(LlmSettings(endpoint = "", model = "").semanticSearchEnabled)
    }
}
