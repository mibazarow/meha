package com.andharness.ru.domain.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

object UsagePatternAnalyzer {

    private const val DAY_MS = 86_400_000L

    /**
     * Mines [events] and returns at most [UsageAnalysisConfig.maxSuggestions] suggestions, best
     * (highest confidence) first. [nowMillis] and [zoneId] are explicit so the result is
     * deterministic in tests; [appLabel] lets the Android layer substitute a human app name while
     * the analyzer itself stays dependency-free (identity by default).
     */
    fun analyze(
        events: List<UsagePatternEvent>,
        config: UsageAnalysisConfig = UsageAnalysisConfig(),
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        appLabel: (String) -> String = { it }
    ): List<AutomationSuggestion> {
        if (events.isEmpty()) return emptyList()

        val windowStart = nowMillis - config.windowDays * DAY_MS
        val window = events.filter { it.startTime in windowStart..nowMillis }
        if (window.isEmpty()) return emptyList()

        val observedDays = window.asSequence()
            .map { dayOf(it.startTime, zoneId) }
            .toHashSet()
            .size
            .coerceAtMost(config.windowDays)
        if (observedDays <= 0) return emptyList()

        val appGroups = HashMap<Pair<String, Int>, MutableList<UsagePatternEvent>>()
        val nightDays = HashMap<Int, MutableSet<LocalDate>>()

        for (event in window) {
            when (event.kind) {
                UsageEventKind.APP_FOREGROUND -> {
                    val pkg = event.packageName.trim()
                    if (pkg.isEmpty() || pkg in config.excludedPackages) continue
                    val duration = event.endTime - event.startTime
                    if (duration > 0 && duration < config.minEventDurationMs) continue
                    val hour = hourOf(event.startTime, zoneId)
                    appGroups.getOrPut(pkg to hour) { mutableListOf() }.add(event)
                }

                UsageEventKind.SCREEN_NON_INTERACTIVE -> {
                    val hour = hourOf(event.startTime, zoneId)
                    if (hour >= config.eveningFromHour || hour <= 5) {
                        nightDays.getOrPut(hour) { mutableSetOf() }.add(dayOf(event.startTime, zoneId))
                    }
                }

                else -> Unit
            }
        }

        // One suggestion per app: its most confident "hour" bucket, ties broken by more events and
        // then by the earlier hour so the output is stable.
        val appSuggestions = appGroups.entries
            .mapNotNull { (key, group) -> appSuggestion(key.first, key.second, group, config, zoneId, observedDays, appLabel) }
            .groupBy { it.packageName }
            .map { (_, candidates) ->
                candidates.sortedWith(
                    compareByDescending<AutomationSuggestion> { it.confidence }
                        .thenByDescending { it.occurrences }
                        .thenBy { it.hourOfDay }
                ).first()
            }

        val nightSuggestion = quietNightSuggestion(nightDays, config, observedDays)

        return (appSuggestions + listOfNotNull(nightSuggestion))
            .sortedWith(
                compareByDescending<AutomationSuggestion> { it.confidence }
                    .thenBy { it.kind.ordinal }
                    .thenBy { it.hourOfDay }
                    .thenBy { it.id }
            )
            .take(config.maxSuggestions)
    }

    private fun appSuggestion(
        pkg: String,
        hour: Int,
        group: List<UsagePatternEvent>,
        config: UsageAnalysisConfig,
        zoneId: ZoneId,
        observedDays: Int,
        appLabel: (String) -> String
    ): AutomationSuggestion? {
        val occurrences = group.size
        if (occurrences < config.minOccurrences) return null
        val days = group.asSequence().map { dayOf(it.startTime, zoneId) }.toHashSet().size
        if (days < config.minDays) return null
        val confidence = days.toDouble() / observedDays
        if (confidence < config.minConfidence) return null

        val label = appLabel(pkg).trim().ifEmpty { pkg }
        val hhmm = hhmm(hour)
        return AutomationSuggestion(
            id = "app:$pkg:$hour",
            kind = SuggestionKind.APP_USAGE,
            title = "«$label» около $hhmm",
            description = "Вы обычно открываете «$label» около $hhmm — $days из $observedDays дней " +
                "(уверенность ${percent(confidence)}%). Правило напомнит уведомлением.",
            confidence = confidence,
            occurrences = occurrences,
            days = days,
            observedDays = observedDays,
            hourOfDay = hour,
            packageName = pkg,
            triggerConfig = hhmm,
            actionConfig = "$label | Пора открыть «$label»"
        )
    }

    /**
     * Bedtime heuristic: the evening/early-morning hour at which the screen went off on the most
     * distinct days, if it repeats often enough. Honest about the platform limit — ANDharness cannot
     * toggle Do-Not-Disturb itself, so the rule only reminds the user.
     */
    private fun quietNightSuggestion(
        nightDays: Map<Int, Set<LocalDate>>,
        config: UsageAnalysisConfig,
        observedDays: Int
    ): AutomationSuggestion? {
        val best = nightDays.entries
            .map { (hour, days) -> Triple(hour, days.size, days) }
            .filter { (_, days, _) -> days >= config.minDays }
            .filter { (_, days, _) -> days.toDouble() / observedDays >= config.minConfidence }
            .sortedWith(compareByDescending<Triple<Int, Int, Set<LocalDate>>> { it.second }.thenBy { it.first })
            .firstOrNull()
            ?: return null

        val hour = best.first
        val days = best.second
        val confidence = days.toDouble() / observedDays
        val hhmm = hhmm(hour)
        return AutomationSuggestion(
            id = "night:$hour",
            kind = SuggestionKind.QUIET_NIGHT,
            title = "Ночной режим около $hhmm",
            description = "Похоже, вы обычно убираете телефон около $hhmm — $days из $observedDays дней. " +
                "Меха не может сам переключить «Не беспокоить» (нет такого доступа), но напомнит уведомлением.",
            confidence = confidence,
            occurrences = days,
            days = days,
            observedDays = observedDays,
            hourOfDay = hour,
            packageName = null,
            triggerConfig = hhmm,
            actionConfig = "Ночной режим | Пора включить «Не беспокоить»"
        )
    }

    private fun hourOf(millis: Long, zoneId: ZoneId): Int =
        Instant.ofEpochMilli(millis).atZone(zoneId).hour

    private fun dayOf(millis: Long, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()

    private fun hhmm(hour: Int): String = String.format(Locale.ROOT, "%02d:00", hour)

    private fun percent(confidence: Double): Int = (confidence * 100).roundToInt()
}
