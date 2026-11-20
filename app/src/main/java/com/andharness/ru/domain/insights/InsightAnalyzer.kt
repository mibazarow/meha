package com.andharness.ru.domain.insights

import com.andharness.ru.domain.audit.ToolAuditEntry
import com.andharness.ru.domain.notify.CapturedNotification
import com.andharness.ru.domain.skill.Skill
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure, Android-free computation of proactive [Insight]s (checklist §1).
 *
 * Design goals, mirroring the task:
 * - **local signals only** — the audit journal, the skill library and the captured-notification
 *   buffer; no network, no model, no guessing;
 * - **statistically justified** — a routine needs enough total samples, enough occurrences on one
 *   weekday, enough distinct weeks and a dominant weekday share, otherwise it is skipped;
 * - **honest about absence** — an empty/insufficient input yields an empty list, and [render] then
 *   returns [EMPTY_MESSAGE], never a fabricated habit, trend or number.
 *
 * Signals with no real history source (battery level over time, storage growth) are intentionally
 * NOT represented here: there is no sampler storing them, so inventing a "trend" would be a lie.
 * When such a source is added, a new builder can be plugged into [build] the same way.
 *
 * `java.time` is available since API 26 (the app's minSdk): https://developer.android.com/reference/java/time/package-summary
 */
object InsightAnalyzer {

    /** Milliseconds in a day, used for the idle/window arithmetic. */
    const val DAY_MS = 86_400_000L

    /** Shown when there is nothing honest to report yet. */
    const val EMPTY_MESSAGE =
        "Инсайтов пока нет: локальных данных для честных выводов недостаточно. " +
            "Меха не выдумывает привычки и тренды — чем дольше она используется и чем больше " +
            "сигналов накапливается (журнал действий, навыки, уведомления), тем содержательнее " +
            "будут подсказки."

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT)

    /** Russian forms for the notification count: (one, few, many). */
    private val NOTIFICATION_FORMS = Triple("уведомление", "уведомления", "уведомлений")

    /**
     * Builds the combined insight list from the three local sources.
     *
     * @param kinds optional filter; empty = all kinds.
     * @param limit hard cap on the returned list.
     * @param toolLabel maps a tool id to a human Russian action label (the Android layer passes
     *   [com.andharness.ru.domain.agent.ToolLabels]); identity by default so the pure logic stays
     *   dependency-free and deterministic in tests.
     * @return insights best-first (confidence, then evidence), or an empty list.
     */
    fun build(
        audit: List<ToolAuditEntry> = emptyList(),
        skills: List<Skill> = emptyList(),
        notifications: List<CapturedNotification> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        config: InsightsConfig = InsightsConfig(),
        kinds: Set<InsightKind> = emptySet(),
        limit: Int = config.maxInsights,
        toolLabel: (String) -> String = { it },
    ): List<Insight> {
        val all = ArrayList<Insight>()
        if (kinds.isEmpty() || InsightKind.ROUTINE in kinds) {
            all += routineInsights(audit, nowMillis, zone, config, toolLabel)
        }
        if (kinds.isEmpty() || InsightKind.STALE_SKILL in kinds) {
            all += staleSkillInsights(skills, nowMillis, zone, config)
        }
        if (kinds.isEmpty() || InsightKind.NOTIFICATION_LOAD in kinds) {
            all += notificationLoadInsights(notifications, nowMillis, config)
        }
        return all
            .sortedWith(
                compareByDescending<Insight> { it.confidence }
                    .thenByDescending { it.evidenceCount }
                    .thenBy { it.kind.ordinal }
                    .thenBy { it.title }
            )
            .take(limit.coerceAtLeast(1))
    }

    /**
     * Recurring-routine insights: for every tool, the weekday on which it is used the most.
     * Reported only when ALL hold inside [InsightsConfig.routineWindowDays]:
     * total invocations >= [InsightsConfig.routineMinSamples], occurrences on that weekday >=
     * [InsightsConfig.routineMinOccurrences], distinct weeks >= [InsightsConfig.routineMinWeeks]
     * and weekday share >= [InsightsConfig.routineMinWeekdayShare]. Confidence comes from the
     * occurrence count ([confidenceOf]).
     */
    fun routineInsights(
        audit: List<ToolAuditEntry>,
        nowMillis: Long,
        zone: ZoneId,
        config: InsightsConfig = InsightsConfig(),
        toolLabel: (String) -> String = { it },
    ): List<Insight> {
        val windowStart = nowMillis - config.routineWindowDays * DAY_MS
        val window = audit.filter { it.at in windowStart..nowMillis && it.tool.isNotBlank() }
        if (window.isEmpty()) return emptyList()

        return window.groupBy { it.tool }
            .mapNotNull { (tool, entries) ->
                routineForTool(tool, entries, zone, config, toolLabel)
            }
            .sortedByDescending { it.confidence }
    }

    private fun routineForTool(
        tool: String,
        entries: List<ToolAuditEntry>,
        zone: ZoneId,
        config: InsightsConfig,
        toolLabel: (String) -> String,
    ): Insight? {
        val total = entries.size
        if (total < config.routineMinSamples) return null

        val dominant = entries.groupBy { weekdayOf(it.at, zone) }
            .entries
            .maxWithOrNull(
                compareBy<Map.Entry<DayOfWeek, List<ToolAuditEntry>>> { it.value.size }
                    .thenByDescending { it.key.value }
            )
            ?: return null

        val occurrences = dominant.value.size
        if (occurrences < config.routineMinOccurrences) return null
        val weekdayShare = occurrences.toDouble() / total
        if (weekdayShare < config.routineMinWeekdayShare) return null

        val weeks = dominant.value
            .asSequence()
            .map { weekBucketOf(it.at, zone) }
            .toHashSet()
            .size
        if (weeks < config.routineMinWeeks) return null

        val label = toolLabel(tool).trim().ifEmpty { tool }
        val day = weekdayPrepositional(dominant.key)
        return Insight(
            kind = InsightKind.ROUTINE,
            title = "Повторяющаяся рутина: $label по $day",
            detail = "Инструмент «$tool» вызывался по $day $occurrences раз(а) за $weeks недел(и) " +
                "(всего $total вызовов за ${config.routineWindowDays} дн.).",
            evidenceCount = occurrences,
            confidence = confidenceOf(occurrences, config),
        )
    }

    /**
     * Stale-skill insights: a skill that has not been used (or, if never used, has existed) for at
     * least [InsightsConfig.staleSkillIdleDays], and is itself at least
     * [InsightsConfig.staleSkillMinAgeDays] old so a freshly saved skill is never nagged about.
     * Evidence is the idle-day count and confidence grows with it (saturating at twice the idle
     * threshold), so a long-dormant skill reads as a stronger signal than one just past the line.
     */
    fun staleSkillInsights(
        skills: List<Skill>,
        nowMillis: Long,
        zone: ZoneId,
        config: InsightsConfig = InsightsConfig(),
    ): List<Insight> {
        val out = ArrayList<Insight>()
        for (skill in skills) {
            if (skill.name.isBlank() || skill.createdAt <= 0L) continue
            val ageDays = daysBetween(skill.createdAt, nowMillis)
            if (ageDays < config.staleSkillMinAgeDays) continue

            val lastUsed = skill.lastUsedAt
            val reference = lastUsed ?: skill.createdAt
            val idleDays = daysBetween(reference, nowMillis)
            if (idleDays < config.staleSkillIdleDays) continue

            val threshold = config.staleSkillIdleDays.toDouble().coerceAtLeast(1.0)
            val detail = if (lastUsed != null) {
                "Навык «${skill.name}» не использовался $idleDays дн. " +
                    "(последний успешный запуск: ${formatDate(lastUsed, zone)})."
            } else {
                "Навык «${skill.name}» создан ${formatDate(skill.createdAt, zone)}, " +
                    "но ни разу не использовался — уже $idleDays дн."
            }
            out += Insight(
                kind = InsightKind.STALE_SKILL,
                title = "Навык не используется: ${skill.name}",
                detail = detail,
                evidenceCount = idleDays,
                confidence = min(1.0, idleDays.toDouble() / (2.0 * threshold)),
            )
        }
        return out.sortedByDescending { it.confidence }
    }

    /**
     * Notification-load insights: the apps that dominate the captured-notification buffer within
     * [InsightsConfig.notificationWindowDays]. Counted only over what is actually in the buffer, and
     * the detail says so, so a small/volatile buffer cannot be mistaken for a full history.
     */
    fun notificationLoadInsights(
        notifications: List<CapturedNotification>,
        nowMillis: Long,
        config: InsightsConfig = InsightsConfig(),
    ): List<Insight> {
        val windowStart = nowMillis - config.notificationWindowDays * DAY_MS
        val window = notifications.filter { it.at in windowStart..nowMillis && it.app.isNotBlank() }
        if (window.isEmpty()) return emptyList()
        val totalInWindow = window.size

        return window.groupBy { it.app }
            .map { (app, items) -> app to items.size }
            .filter { (_, count) -> count >= config.notificationMinCount }
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }
            )
            .take(config.notificationMaxApps)
            .map { (app, count) ->
                Insight(
                    kind = InsightKind.NOTIFICATION_LOAD,
                    title = "Больше всего уведомлений: $app",
                    detail = "В буфере захвата за последние ${config.notificationWindowDays} дн. — " +
                        "$count ${pluralRu(count, NOTIFICATION_FORMS)} от «$app» " +
                        "(всего за окно: $totalInWindow).",
                    evidenceCount = count,
                    confidence = confidenceOf(count, config),
                )
            }
    }

    /** Renders the list, or the honest empty state when nothing qualifies. */
    fun render(insights: List<Insight>): String {
        if (insights.isEmpty()) return EMPTY_MESSAGE
        return buildString {
            append("Инсайты по локальным данным (").append(insights.size).append("):")
            for (insight in insights) {
                append("\n- ").append(insight.title).append(" — ").append(insight.detail)
                append(" [наблюдений: ").append(insight.evidenceCount)
                append(", уверенность ").append(percent(insight.confidence)).append("%]")
            }
        }
    }

    /**
     * Picks the correct Russian plural form for a count.
     *
     * @param n the count (its absolute value is used).
     * @param forms the three Russian forms `(one, few, many)`, e.g.
     *   `("уведомление", "уведомления", "уведомлений")`.
     * @return `one` for 1, 21, 101…; `few` for 2–4, 22–24…; `many` otherwise (0, 5–20 and the teens
     *   11–14 always take `many`, so 11 → many even though it ends in 1).
     */
    fun pluralRu(n: Int, forms: Triple<String, String, String>): String {
        val withinHundred = kotlin.math.abs(n) % 100
        return when {
            withinHundred in 11..14 -> forms.third
            withinHundred % 10 == 1 -> forms.first
            withinHundred % 10 in 2..4 -> forms.second
            else -> forms.third
        }
    }

    /** Sample-size based confidence: `min(1, evidence / confidenceSamples)`. */
    private fun confidenceOf(evidence: Int, config: InsightsConfig): Double =
        min(1.0, evidence.toDouble() / config.confidenceSamples.coerceAtLeast(1).toDouble())

    private fun daysBetween(fromMillis: Long, toMillis: Long): Int =
        ((toMillis - fromMillis) / DAY_MS).toInt().coerceAtLeast(0)

    private fun weekdayOf(millis: Long, zone: ZoneId): DayOfWeek =
        Instant.ofEpochMilli(millis).atZone(zone).dayOfWeek

    /** Distinct-week bucket; monotonic, only used for counting, so ISO alignment is not needed. */
    private fun weekBucketOf(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay() / 7L

    private fun formatDate(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(DATE_FORMAT)

    private fun weekdayPrepositional(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "понедельникам"
        DayOfWeek.TUESDAY -> "вторникам"
        DayOfWeek.WEDNESDAY -> "средам"
        DayOfWeek.THURSDAY -> "четвергам"
        DayOfWeek.FRIDAY -> "пятницам"
        DayOfWeek.SATURDAY -> "субботам"
        DayOfWeek.SUNDAY -> "воскресеньям"
    }

    private fun percent(confidence: Double): Int =
        (confidence * 100).roundToInt().coerceIn(0, 100)
}
