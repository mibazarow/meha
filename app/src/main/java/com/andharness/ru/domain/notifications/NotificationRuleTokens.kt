package com.andharness.ru.domain.notifications

import java.util.Locale

/**
 * Lenient RU/EN token parsing for the `notification_rules_set` tool arguments. Kept separate from the
 * serializable enums so the wire format stays a plain enum name and the tool vocabulary can evolve
 * (aliases) without touching serialization.
 */
object NotificationRuleTokens {

    /** [RuleImportance] for a user token (`mute`/`важное`/...); `null` when unrecognized. */
    fun importance(raw: String?): RuleImportance? {
        val token = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when (token) {
            "mute", "off", "silent", "заглушить", "тихо", "без", "выкл" -> RuleImportance.MUTE
            "normal", "default", "обычно", "обычная", "нормально" -> RuleImportance.NORMAL
            "important", "high", "важно", "важное", "важный" -> RuleImportance.IMPORTANT
            else -> null
        }
    }

    /** [SummaryMode] for a user token; `null` when unrecognized. */
    fun summaryMode(raw: String?): SummaryMode? {
        val token = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when (token) {
            "off", "none", "выкл", "нет", "тишина" -> SummaryMode.OFF
            "important_only", "important", "only_important", "важное", "только_важное" -> SummaryMode.IMPORTANT_ONLY
            "all", "всё", "все" -> SummaryMode.ALL
            else -> null
        }
    }
}
