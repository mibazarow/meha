package com.andharness.ru.domain.insights


/**
 * Conservative tunables of [InsightAnalyzer]. Defaults are chosen so an insight only appears once
 * the pattern is statistically justified, and are explicit so tests can lower them.
 */
data class InsightsConfig(
    /** How far back the audit journal is mined for recurring routines. */
    val routineWindowDays: Int = 28,
    /** Minimum total invocations of a tool before a weekday pattern is even considered. */
    val routineMinSamples: Int = 6,
    /** Minimum invocations that must fall on the dominant weekday. */
    val routineMinOccurrences: Int = 4,
    /** Minimum number of distinct calendar weeks the dominant weekday must repeat across. */
    val routineMinWeeks: Int = 3,
    /** Minimum share of the tool's invocations that must fall on the dominant weekday. */
    val routineMinWeekdayShare: Double = 0.5,
    /** A skill must be at least this old before its idleness is reported. */
    val staleSkillMinAgeDays: Int = 30,
    /** A skill must be idle at least this long (since last successful run, else since creation). */
    val staleSkillIdleDays: Int = 30,
    /** Window of the captured-notification buffer used for the notification-load insight. */
    val notificationWindowDays: Int = 7,
    /** Minimum notifications from one app before it is reported. */
    val notificationMinCount: Int = 3,
    /** Cap on reported apps in the notification-load section. */
    val notificationMaxApps: Int = 5,
    /** Evidence size at which confidence saturates to 1.0 (`confidence = min(1, evidence / this)`). */
    val confidenceSamples: Int = 8,
    /** Hard cap on the combined result. */
    val maxInsights: Int = 20,
)
