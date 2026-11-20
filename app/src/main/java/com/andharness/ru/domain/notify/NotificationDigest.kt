package com.andharness.ru.domain.notify

import com.andharness.ru.domain.notifications.SummaryMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatter of the `notifications_digest` tool output: groups the captured events by app and
 * marks the important ones. No Android dependencies, so the layout is unit-testable.
 */
object NotificationDigest {

    const val DEFAULT_LIMIT = 30

    /**
     * @param entries newest first, as returned by [NotificationCaptureLog.recent]
     * @param mode the user's `summary_mode` rule: [SummaryMode.ALL] (default, pre-rules behaviour)
     *   shows everything, [SummaryMode.IMPORTANT_ONLY] only HIGH events, [SummaryMode.OFF] nothing.
     * @return a grouped digest, or an honest empty-state line.
     */
    fun build(
        entries: List<CapturedNotification>,
        limit: Int = DEFAULT_LIMIT,
        mode: SummaryMode = SummaryMode.ALL,
    ): String {
        if (mode == SummaryMode.OFF) {
            return "Сводка уведомлений отключена правилом (summary_mode=off). Измените его через notification_rules_set."
        }
        if (entries.isEmpty()) {
            return "Свежих захваченных уведомлений нет. Проверьте, что выдан доступ к уведомлениям " +
                "(notification access) и нужные приложения выбраны в messaging_index_config."
        }
        val filtered = when (mode) {
            SummaryMode.IMPORTANT_ONLY -> entries.filter { it.importance == TriageImportance.HIGH }
            SummaryMode.ALL -> entries
            SummaryMode.OFF -> emptyList()
        }
        if (filtered.isEmpty()) {
            return "В выбранном режиме (только важное) среди ${entries.size} последних уведомлений важных нет."
        }
        val bounded = filtered.take(limit.coerceAtLeast(1))
        val important = bounded.count { it.importance == TriageImportance.HIGH }
        val sb = StringBuilder()
        val scope = if (mode == SummaryMode.IMPORTANT_ONLY) " (только важное)" else ""
        sb.append("Сводка уведомлений$scope: ${bounded.size} из ${entries.size}, важных: $important.")
        val byApp = LinkedHashMap<String, MutableList<CapturedNotification>>()
        bounded.forEach { entry ->
            byApp.getOrPut(entry.app) { mutableListOf() }.add(entry)
        }
        byApp.forEach { (app, items) ->
            sb.append("\n[${app}] (${items.size}):")
            items.forEach { item ->
                val mark = when (item.importance) {
                    TriageImportance.HIGH -> "ВАЖНО"
                    TriageImportance.NORMAL -> "обычное"
                    TriageImportance.LOW -> "низкое"
                }
                val time = if (item.at > 0) " | ${formatTime(item.at)}" else ""
                sb.append("\n  - [$mark] ${item.summary}$time")
            }
        }
        return sb.toString()
    }

    private fun formatTime(epochMillis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
}
