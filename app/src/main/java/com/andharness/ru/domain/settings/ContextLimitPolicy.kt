package com.andharness.ru.domain.settings

/**
 * The rule for the model context window. It is DERIVED automatically from what the provider reports
 * for the selected model (the `/models` payload), with an OPTIONAL manual override the user can type
 * when they are confident the detection is wrong — different providers report the limit in different
 * fields, and covering all of them is impractical.
 *
 * Priority: manual override when positive, else the provider maximum (used in full — no invented
 * reduction), else a fixed honest default ([DEFAULT_CONTEXT_TOKENS]). The compression budget stays a
 * separate 80 % derivation (see [CompressionBudgetPolicy]).
 *
 * Pure and Android-free so the derivation is JVM-testable.
 */
object ContextLimitPolicy {

    /** Context window assumed when neither the manual override nor the provider reports a maximum. */
    const val DEFAULT_CONTEXT_TOKENS = 100_000L

    /**
     * Effective context: [manualOverride] when it is a positive number, else [providerMax] when it is
     * a positive number, else [DEFAULT_CONTEXT_TOKENS]. Never null, so the context is always defined.
     */
    fun effectiveContext(manualOverride: Long?, providerMax: Long?): Long =
        manualOverride?.takeIf { it > 0 }
            ?: providerMax?.takeIf { it > 0 }
            ?: DEFAULT_CONTEXT_TOKENS
}
