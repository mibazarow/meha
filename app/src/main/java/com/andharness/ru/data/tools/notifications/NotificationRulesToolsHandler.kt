package com.andharness.ru.data.tools.notifications

import com.andharness.ru.data.notifications.NotificationRulesStore
import com.andharness.ru.data.tools.boolOrNull
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.notifications.NotificationRules
import com.andharness.ru.domain.notifications.NotificationRulesCodec
import com.andharness.ru.domain.notifications.NotificationRuleTokens
import com.andharness.ru.domain.notifications.QuietHours
import com.andharness.ru.domain.notifications.RuleImportance
import com.andharness.ru.domain.notifications.SummaryMode
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * Tools over the user's own notification rules ([NotificationRulesStore], checklist §1):
 *
 * - `notification_rules_list` — read-only view of the per-app rules + quiet hours + summary mode;
 * - `notification_rules_set`  — set/update one per-app rule and/or the global quiet hours and summary
 *   mode. Every parameter is optional and simple (string/boolean); unrecognized values are rejected
 *   with an honest Russian message instead of being guessed.
 *
 * These tools only change Меха's own policy: per-app `MUTE` stops Меха from surfacing the event, it
 * never dismisses or silences the real system notification (the listener observes, it does not
 * control the status bar) — matching the platform model where silencing is the user's Do Not Disturb
 * / channel-importance decision, not the app's:
 * https://developer.android.com/reference/android/app/NotificationManager#INTERRUPTION_FILTER_PRIORITY
 */
class NotificationRulesToolsHandler(private val store: NotificationRulesStore) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "notification_rules_list"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                runCatching { ToolResult(true, render(store.current())) }
                    .getOrElse { ToolResult(false, "Ошибка чтения правил уведомлений: ${it.message}") }
            }
        },
        object : ToolHandler {
            override val name = "notification_rules_set"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    apply(arguments)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка сохранения правил уведомлений: ${e.message}")
                }
            }
        },
    )

    /** Applies one `notification_rules_set` call; returns the honest outcome (or the first error). */
    private fun apply(arguments: JsonObject): ToolResult {
        val pkg = arguments.str("package")?.trim()?.takeIf { it.isNotEmpty() }
        val importanceToken = arguments.str("importance")
        val note = arguments.str("note")
        val remove = arguments.bool("remove")
        val quietStart = arguments.str("quiet_start")
        val quietEnd = arguments.str("quiet_end")
        val quietOff = arguments.bool("quiet_off")
        val allowImportant = arguments.boolOrNull("quiet_allow_important")
        val summaryToken = arguments.str("summary_mode")

        val hasAny = pkg != null || importanceToken != null || note != null || remove == true ||
            quietStart != null || quietEnd != null || quietOff == true ||
            allowImportant != null || summaryToken != null
        if (!hasAny) {
            return ToolResult(
                false,
                "Не указано ни одного параметра. Задайте package+importance (правило приложения), " +
                    "quiet_start и quiet_end (тихие часы), quiet_allow_important, summary_mode или remove."
            )
        }

        // Per-app rule.
        if (remove == true && pkg == null) {
            val cleared = store.removeAppRule(null)
            return ToolResult(true, if (cleared) "Все правила приложений удалены." else "Правил приложений и не было.")
        }
        if (remove == true && pkg != null) {
            val removed = store.removeAppRule(pkg)
            return ToolResult(
                true,
                if (removed) "Правило для «$pkg» удалено." else "Правила для «$pkg» не было."
            )
        }
        if (pkg != null || importanceToken != null || note != null) {
            if (pkg == null) {
                return ToolResult(false, "Укажите package, чтобы задать правило приложения.")
            }
            val importance = NotificationRuleTokens.importance(importanceToken)
                ?: return ToolResult(
                    false,
                    "Неизвестная важность «${importanceToken ?: ""}». Допустимо: mute, normal, important."
                )
            store.setAppRule(pkg, importance, note)
        }

        // Global quiet hours.
        if (quietOff == true) {
            store.clearQuietHours()
        } else if (quietStart != null || quietEnd != null) {
            if (quietStart == null || quietEnd == null) {
                return ToolResult(false, "Для тихих часов укажите и quiet_start, и quiet_end (формат ЧЧ:ММ).")
            }
            val start = parseClock(quietStart)
                ?: return ToolResult(false, "Не разобрал quiet_start «$quietStart»; нужен формат ЧЧ:ММ (например 23:00).")
            val end = parseClock(quietEnd)
                ?: return ToolResult(false, "Не разобрал quiet_end «$quietEnd»; нужен формат ЧЧ:ММ (например 07:00).")
            store.setQuietHours(QuietHours(start, end))
        }
        if (allowImportant != null) store.setQuietAllowImportant(allowImportant)
        if (summaryToken != null) {
            val mode = NotificationRuleTokens.summaryMode(summaryToken)
                ?: return ToolResult(false, "Неизвестный режим сводки «$summaryToken». Допустимо: off, important_only, all.")
            store.setSummaryMode(mode)
        }

        return ToolResult(true, "Сохранено.\n${render(store.current())}")
    }

    /** Read-only rendering of the whole rule set (Russian, grouped). */
    private fun render(rules: NotificationRules): String = buildString {
        appendLine("Правила уведомлений (${rules.apps.size}/${NotificationRulesCodec.MAX_APPS} приложений):")
        val quiet = rules.quietHours
        if (quiet == null) {
            appendLine("- Тихие часы: выключены")
        } else {
            val allow = if (rules.quietAllowImportant) "важные разрешены" else "молчат и важные"
            appendLine("- Тихие часы: ${formatClock(quiet.startMinute)}–${formatClock(quiet.endMinute)} ($allow)")
        }
        appendLine("- Режим сводки: ${summaryLabel(rules.summaryMode)}")
        if (rules.apps.isEmpty()) {
            appendLine("- Правил по приложениям нет")
        } else {
            appendLine("- Правила по приложениям:")
            rules.apps.values.forEach { rule ->
                val note = rule.note?.let { " — «$it»" } ?: ""
                appendLine("  • ${rule.appPackage}: ${importanceLabel(rule.importance)}$note")
            }
        }
    }.trim()

    private fun importanceLabel(importance: RuleImportance): String = when (importance) {
        RuleImportance.MUTE -> "БЕЗ УВЕДОМЛЕНИЙ"
        RuleImportance.NORMAL -> "обычно"
        RuleImportance.IMPORTANT -> "ВАЖНОЕ"
    }

    private fun summaryLabel(mode: SummaryMode): String = when (mode) {
        SummaryMode.OFF -> "выключена"
        SummaryMode.IMPORTANT_ONLY -> "только важное"
        SummaryMode.ALL -> "все уведомления"
    }

    private fun formatClock(minuteOfDay: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

    /** Parses `HH:MM` or a bare hour (`7` → 07:00) into minutes since midnight; `null` if invalid. */
    private fun parseClock(raw: String): Int? {
        val value = raw.trim()
        val parts = value.split(':')
        val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val minute = if (parts.size == 1) 0 else parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun JsonObject.bool(key: String): Boolean? = boolOrNull(key)
}
