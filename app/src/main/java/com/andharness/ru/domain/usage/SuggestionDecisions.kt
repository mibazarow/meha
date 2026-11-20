package com.andharness.ru.domain.usage

/**
 * Pure filtering of mined suggestions against the user's persisted decisions (update.md idea #16).
 *
 * Kept separate from [UsagePatternAnalyzer] so "already accepted / dismissed never reappears" is a
 * plain, JVM-testable function with no Android dependency; the Android engine only supplies the
 * handled-id set from its preferences store.
 */
object SuggestionDecisions {

    /** The suggestions whose id is not in [handledIds], order preserved. */
    fun visible(
        suggestions: List<AutomationSuggestion>,
        handledIds: Set<String>
    ): List<AutomationSuggestion> = suggestions.filterNot { it.id in handledIds }
}
