package com.andharness.ru.domain.notifications

import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.time.ZoneId

/**
 * The complete rule set. **The defaults are a strict no-op**: no app rules, no quiet hours, important
 * notifications allowed, `summaryMode = ALL` — i.e. behaviour identical to before the feature existed.
 * That is what makes the rules safe to ship: with an empty file nothing changes.
 */

@Serializable
data class NotificationRules(
    val apps: Map<String, AppRule> = emptyMap(),
    val quietHours: QuietHours? = null,
    val quietAllowImportant: Boolean = true,
    val summaryMode: SummaryMode = SummaryMode.ALL,
) {

    /** Instance convenience over [classify]; unknown/blank apps are [RuleImportance.NORMAL]. */
    fun classify(appPackage: String?): RuleImportance = classify(appPackage, this)

    /** Instance convenience over [isQuiet]. */
    fun isQuiet(now: LocalTime): Boolean = isQuiet(now, quietHours)

    companion object {

        /**
         * Pure lookup: the [RuleImportance] for [appPackage], or [RuleImportance.NORMAL] when there is
         * no rule (permissive default). Exact match first, then a case-insensitive one, matching the
         * store's lookup policy so a rule saved as `Org.Telegram.Messenger` still applies.
         */
        fun classify(appPackage: String?, rules: NotificationRules): RuleImportance {
            val target = appPackage?.trim().orEmpty()
            if (target.isEmpty()) return RuleImportance.NORMAL
            val rule = rules.apps[target]
                ?: rules.apps.entries.firstOrNull { it.key.equals(target, ignoreCase = true) }?.value
            return rule?.importance ?: RuleImportance.NORMAL
        }

        /**
         * Pure time check for the quiet window: [startMinute] inclusive, [endMinute] exclusive, and a
         * window wraps past midnight when start > end. A `null` window (or start == end) is never
         * quiet, so the default is a no-op.
         */
        fun isQuiet(now: LocalTime, quietHours: QuietHours?): Boolean {
            if (quietHours == null) return false
            val start = quietHours.startMinute
            val end = quietHours.endMinute
            if (start == end) return false
            val minute = now.hour * 60 + now.minute
            return if (start < end) {
                minute in start until end
            } else {
                minute >= start || minute < end
            }
        }

        /** Epoch-millis convenience used by the triage path (local zone by default). */
        fun isQuiet(nowMillis: Long, quietHours: QuietHours?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
            if (quietHours == null) return false
            val time = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalTime()
            return isQuiet(time, quietHours)
        }
    }
}
