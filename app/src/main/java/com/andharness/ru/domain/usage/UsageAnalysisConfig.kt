package com.andharness.ru.domain.usage


/** Tunables of [UsagePatternAnalyzer.analyze]; the defaults are deliberately conservative. */
data class UsageAnalysisConfig(
    /** How far back to look. Events older than this are ignored. */
    val windowDays: Int = 14,
    /** Minimum number of qualifying events for an app/hour pattern. */
    val minOccurrences: Int = 4,
    /** Minimum number of distinct days the pattern must repeat on. */
    val minDays: Int = 3,
    /** Minimum confidence (distinct days / observed days). */
    val minConfidence: Double = 0.5,
    /** Upper bound on returned suggestions. */
    val maxSuggestions: Int = 10,
    /**
     * Foreground events shorter than this are treated as noise (app switch-through). Events whose
     * duration is unknown (end <= start) bypass this filter.
     */
    val minEventDurationMs: Long = 2 * 60_000L,
    /** Local hour from which a screen-off event counts as "going to bed" (evening). */
    val eveningFromHour: Int = 18,
    /** Packages to ignore entirely (e.g. the harness' own package, launchers, system UI). */
    val excludedPackages: Set<String> = emptySet()
)
