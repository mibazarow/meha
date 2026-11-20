package com.andharness.ru.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The model context is derived from the optional manual override when set, else the provider
 * maximum (used in full — no invented reduction), else the fixed default.
 */
class ContextLimitPolicyTest {

    @Test
    fun manualOverrideWins() {
        assertEquals(1_000_000L, ContextLimitPolicy.effectiveContext(1_000_000L, 65_536L))
        assertEquals(200_000L, ContextLimitPolicy.effectiveContext(200_000L, null))
    }

    @Test
    fun providerMaxUsedWhenNoManualOverride() {
        assertEquals(200_000L, ContextLimitPolicy.effectiveContext(null, 200_000L))
        assertEquals(8_000L, ContextLimitPolicy.effectiveContext(null, 8_000L))
    }

    @Test
    fun missingOrNonPositiveValuesFallBackToTheDefault() {
        assertEquals(100_000L, ContextLimitPolicy.effectiveContext(null, null))
        assertEquals(100_000L, ContextLimitPolicy.effectiveContext(0L, 0L))
        assertEquals(100_000L, ContextLimitPolicy.effectiveContext(-5L, -1L))
        // A non-positive manual override still falls through to the provider max.
        assertEquals(200_000L, ContextLimitPolicy.effectiveContext(0L, 200_000L))
    }

    @Test
    fun defaultIsOneHundredThousandTokens() {
        assertEquals(100_000L, ContextLimitPolicy.DEFAULT_CONTEXT_TOKENS)
    }
}
