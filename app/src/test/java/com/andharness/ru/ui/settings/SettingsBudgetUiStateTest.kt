package com.andharness.ru.ui.settings

import com.andharness.ru.domain.settings.ContextLimitPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line the settings screen shows must equal what Save persists: the compression budget is ALWAYS
 * 80 % of the auto-derived model context (there is no user-facing budget or context setting).
 */
class SettingsBudgetUiStateTest {

    @Test
    fun effectiveBudgetDerivesFromTheProviderMaxContext() {
        val state = SettingsUiState(modelContextTokens = 120_000L)
        assertEquals(96_000L, state.effectiveCompressionBudgetTokens)
    }

    @Test
    fun effectiveBudgetDerivesFromTheDefaultContextWhenProviderIsSilent() {
        val effective = ContextLimitPolicy.effectiveContext(null, null)
        val state = SettingsUiState(modelContextTokens = effective)
        assertEquals(80_000L, state.effectiveCompressionBudgetTokens)
    }

    @Test
    fun effectiveBudgetIsNullWhenContextIsUnknown() {
        assertNull(SettingsUiState().effectiveCompressionBudgetTokens)
    }

    @Test
    fun explanationUsesProviderMaxVariantOnlyWhenTheProviderReportedOne() {
        assertTrue(SettingsUiState(modelMaxContextTokens = 200_000L).contextExplanationUsesProviderMax)
        assertFalse(SettingsUiState(modelMaxContextTokens = null).contextExplanationUsesProviderMax)
    }
}
