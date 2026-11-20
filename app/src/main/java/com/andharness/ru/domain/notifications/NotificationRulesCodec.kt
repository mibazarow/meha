package com.andharness.ru.domain.notifications

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure encode/decode/sanitize logic for [NotificationRules]. A corrupt/foreign/missing blob degrades
 * to the no-op defaults instead of throwing, and every field is clamped, so a hand-edited file can
 * never grow the settings unbounded or crash the triage.
 */
object NotificationRulesCodec {

    /** Hard cap on stored per-app rules; the file is a human preference, not a database. */
    const val MAX_APPS = 200

    /** Hard cap on a rule note, so a note can never carry a message body. */
    const val MAX_NOTE_CHARS = 200

    /** Hard cap on a package id length (Android package names are far shorter). */
    const val MAX_PACKAGE_CHARS = 200

    private val json = AppJson.LenientEncodeDefaults

    private val pretty = AppJson.LenientEncodeDefaultsPretty

    /** Compact form persisted to disk. */
    fun encode(rules: NotificationRules): String = json.encodeToString(sanitize(rules))

    /** Pretty form used by `notification_rules_list` so a human/agent can read the rules. */
    fun pretty(rules: NotificationRules): String = pretty.encodeToString(sanitize(rules))

    /**
     * Decodes the persisted rule set. A corrupt/foreign blob degrades to the no-op defaults, blank
     * packages are dropped, and the map is capped at [MAX_APPS] (newest entries kept).
     */
    fun decode(raw: String?): NotificationRules =
        json.decodeOrNull<NotificationRules>(raw)?.let { sanitize(it) } ?: NotificationRules()

    /** Clamps and drops invalid entries so any input maps to a safe, bounded rule set. */
    fun sanitize(rules: NotificationRules): NotificationRules {
        val kept = rules.apps.entries
            .asSequence()
            .mapNotNull { (key, rule) ->
                val pkg = key.trim().take(MAX_PACKAGE_CHARS)
                if (pkg.isEmpty()) null
                else pkg to rule.copy(
                    appPackage = pkg,
                    note = rule.note?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NOTE_CHARS),
                )
            }
            .toList()
            .takeLast(MAX_APPS)
        val apps = LinkedHashMap<String, AppRule>(kept.size)
        for ((pkg, rule) in kept) apps[pkg] = rule
        return rules.copy(
            apps = apps,
            quietHours = rules.quietHours?.let {
                QuietHours(it.startMinute.coerceIn(0, 1439), it.endMinute.coerceIn(0, 1439))
            },
        )
    }
}
