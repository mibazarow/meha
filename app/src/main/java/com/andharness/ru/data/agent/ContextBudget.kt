package com.andharness.ru.data.agent

/**
 * Decides when the conversation must be compacted and how far it must be trimmed.
 *
 * The budget ([com.andharness.ru.domain.settings.LlmSettings.contextCompressionTokens]) is derived
 * from the model's real context window (≈80% of it, see `SettingsViewModel`). Compaction fires when
 * the estimated request reaches [TRIGGER_PERCENT] of that budget — i.e. well before the real context
 * limit — and trims the history down to [TARGET_PERCENT]. The gap between the two is the safety
 * margin (hysteresis): after one compaction the very next request must not re-compact immediately.
 */
internal object ContextBudget {
    /** Compaction fires at this share of the budget (≈80%). */
    const val TRIGGER_PERCENT: Long = 80L

    /** After compaction the history is trimmed to this share of the budget. */
    const val TARGET_PERCENT: Long = 60L

    fun triggerThreshold(budget: Long): Long = if (budget <= 0L) 0L else budget * TRIGGER_PERCENT / 100

    fun targetThreshold(budget: Long): Long = if (budget <= 0L) 0L else budget * TARGET_PERCENT / 100

    /** True when the request is close enough to the budget to need compaction. */
    fun shouldCompact(estimatedTokens: Long, budget: Long): Boolean =
        budget > 0L && estimatedTokens > triggerThreshold(budget)

    /** The effective budget for the active settings: the user's configured token budget. */
    fun budgetFor(contextCompressionTokens: Long): Long = contextCompressionTokens

    /** The effective compaction trigger threshold for the active settings. */
    fun triggerFor(contextCompressionTokens: Long): Long = triggerThreshold(contextCompressionTokens)

    /** The effective post-compaction target threshold for the active settings. */
    fun targetFor(contextCompressionTokens: Long): Long = targetThreshold(contextCompressionTokens)

    /** True when the request has reached the explicit [trigger] threshold. */
    fun shouldCompactAt(estimatedTokens: Long, trigger: Long): Boolean =
        trigger > 0L && estimatedTokens > trigger
}

