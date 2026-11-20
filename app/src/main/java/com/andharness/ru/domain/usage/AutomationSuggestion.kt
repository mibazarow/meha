package com.andharness.ru.domain.usage


/**
 * A candidate automation, already shaped into a concrete, valid rule (trigger `time` + action
 * `notify`) so accepting it can call the normal rule-creation path without further interpretation.
 *
 * [id] is stable and derived from the pattern (not random) so accepted/dismissed decisions survive
 * re-analysis and process restarts.
 */
data class AutomationSuggestion(
    val id: String,
    val kind: SuggestionKind,
    val title: String,
    val description: String,
    /** 0.0–1.0; the share of observed days the pattern occurred on. */
    val confidence: Double,
    /** How many qualifying events formed the pattern. */
    val occurrences: Int,
    /** How many distinct days the pattern occurred on. */
    val days: Int,
    /** How many distinct days had any usage at all in the window (the confidence denominator). */
    val observedDays: Int,
    val hourOfDay: Int,
    val packageName: String? = null,
    val triggerType: String = "time",
    val triggerConfig: String,
    val actionType: String = "notify",
    val actionConfig: String
)
