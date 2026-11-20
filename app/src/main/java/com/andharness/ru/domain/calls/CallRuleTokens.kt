package com.andharness.ru.domain.calls

import java.util.Locale

/**
 * Lenient RU/EN boolean token parsing for the `call_rules_set` tool. Kept separate from the
 * serializable model so the tool vocabulary can accept aliases without touching the wire format.
 */
object CallRuleTokens {

    /** A user boolean token (`true`/`да`/`вкл`/...); `null` when unrecognized. */
    fun bool(raw: String?): Boolean? {
        val token = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when (token) {
            "true", "1", "on", "yes", "да", "вкл", "включить", "включено" -> true
            "false", "0", "off", "no", "нет", "выкл", "выключить", "выключено" -> false
            else -> null
        }
    }
}
