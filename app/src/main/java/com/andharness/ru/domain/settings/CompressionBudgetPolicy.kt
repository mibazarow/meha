package com.andharness.ru.domain.settings

/**
 * The single, honest rule for the context-compression budget (the value the chat indicator shows as
 * «использовано X / N», persisted as `settings.contextCompressionTokens`).
 *
 * The budget is ALWAYS DERIVED from the model context window (80 %): there is no user-facing budget
 * setting. The context itself is auto-derived from the provider maximum (see [ContextLimitPolicy]);
 * the budget is recomputed from it on every save. Pure and Android-free so the derivation is
 * JVM-testable.
 */
object CompressionBudgetPolicy {

    /** Compression budget as a percentage of the model context window. */
    const val PERCENT: Long = 80L

    /** Derived budget (80 % of [modelContextTokens]), or null when the context is unknown or <= 0. */
    fun derive(modelContextTokens: Long?): Long? {
        val context = modelContextTokens?.takeIf { it > 0 } ?: return null
        return context * PERCENT / 100
    }
}
