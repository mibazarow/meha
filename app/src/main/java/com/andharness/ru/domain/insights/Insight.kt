package com.andharness.ru.domain.insights

/**
 * Proactive, **data-backed** insights about the user's own usage (checklist §1 «Проактивные
 * инсайты»). Every insight is derived ONLY from local signals the app already owns — the tool-call
 * audit journal ([com.andharness.ru.domain.audit.ToolAuditEntry]), the app-skill library
 * ([com.andharness.ru.domain.skill.Skill]) and the captured-notification buffer
 * ([com.andharness.ru.domain.notify.CapturedNotification]) — and is skipped entirely when there is
 * not enough evidence. Nothing is ever fabricated: an empty input produces an empty list, never a
 * guessed habit or trend.
 *
 * The model is deliberately Android-free (plain Kotlin + `java.time`, available since API 26), so
 * the analysis is exercised on the JVM; the assembler that reads the stores lives in
 * `data/insights/InsightsAssembler.kt`.
 */

/**
 * One honest insight.
 *
 * [title] is a short Russian headline, [detail] the supporting sentence with the concrete numbers.
 * [evidenceCount] is the raw sample the insight rests on (tool invocations, idle days, or
 * notifications), and [confidence] is a 0.0–1.0 value **derived from that sample size** — it grows
 * with the evidence and saturates once the sample is comfortably large (see
 * [InsightsConfig.confidenceSamples]); it is never an invented probability.
 */
data class Insight(
    val kind: InsightKind,
    val title: String,
    val detail: String,
    val evidenceCount: Int,
    val confidence: Double,
)
