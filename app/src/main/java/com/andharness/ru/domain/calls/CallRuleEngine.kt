package com.andharness.ru.domain.calls

import com.andharness.ru.domain.formatting.WHITESPACE
import java.util.Locale

/**
 * Pure policy for call/SMS auto-handling. All functions are side-effect free and unit-testable.
 *
 * Precedence inside [decideCall] (most specific first):
 * 1. rules disabled → ALLOW (opt-in no-op);
 * 2. allowlisted → ALLOW (the allowlist always passes, and is never auto-replied to);
 * 3. blocked → REJECT;
 * 4. unknown + [CallRules.blockUnknown] → REJECT;
 * 5. otherwise ALLOW, or ALLOW_AND_REPLY when the caller is an unknown, reply-eligible number and
 *    [CallRules.autoReplySms] is on with a non-blank text.
 *
 * An unknown caller that is rejected because of [CallRules.blockUnknown] still receives the
 * auto-reply SMS at the integration layer (a rejected call plus a "I'll call you back" message is the
 * whole point of the feature); [shouldAutoReply] is that independent gate.
 */
object CallRuleEngine {

    /** The GSM/EU emergency number plus the RU/US emergency short codes that must never be replied to. */
    private val EMERGENCY_NUMBERS = setOf(
        "112", "911", "000",
        "100", "101", "102", "103", "104", "105", "106", "107", "108",
    )

    /** Digits that a real dialable number needs before it can receive an automatic reply. */
    const val MIN_REPLY_DIGITS = 7

    /**
     * The call decision for [number] given whether it [contactKnown] and the [rules]. Never throws;
     * a blank/unparseable number is treated as an unknown non-allowlisted caller.
     */
    fun decideCall(number: String?, contactKnown: Boolean, rules: CallRules): CallDecision {
        if (!rules.enabled) return CallDecision.ALLOW
        val normalized = normalizeNumber(number)

        // 2. The allowlist always passes and is never auto-replied to.
        if (normalized != null && contains(rules.allowNumbers, normalized)) return CallDecision.ALLOW

        // 3. Explicitly blocked numbers win over everything except the allowlist.
        if (normalized != null && contains(rules.blockedNumbers, normalized)) return CallDecision.REJECT

        // 4. Spam/unknown.
        if (rules.blockUnknown && !contactKnown) return CallDecision.REJECT

        // 5. Known contacts are never auto-replied to; otherwise reply when configured and safe.
        if (contactKnown) return CallDecision.ALLOW
        val canReply = rules.autoReplySms &&
            rules.autoReplyText.isNotBlank() &&
            isReplyEligible(normalized)
        return if (canReply) CallDecision.ALLOW_AND_REPLY else CallDecision.ALLOW
    }

    /**
     * Independent auto-reply gate used by the SMS path: reply only to a **not known** number and only
     * when [CallRules.autoReplySms] is on and the whole feature is [CallRules.enabled]. The number-level
     * safety (short codes, emergency, allowlist, cooldown) is applied by the caller via
     * [isReplyEligible] and [AutoReplyCooldown] — this function is the pure "should we consider it".
     */
    fun shouldAutoReply(numberKnown: Boolean, rules: CallRules): Boolean =
        rules.enabled && rules.autoReplySms && !numberKnown

    /**
     * `true` when the device is busy (already in a call) and [CallRules.autoReplyWhenBusy] is on.
     * Used by the SMS path, where "busy" is the reason to reply even to a known number.
     */
    fun shouldAutoReplyWhenBusy(inCall: Boolean, rules: CallRules): Boolean =
        rules.enabled && rules.autoReplyWhenBusy && inCall

    /**
     * Safety predicate: whether [number] may receive an automatic reply at all. Rejects blank/alphanumeric
     * senders, short codes/service numbers (fewer than [MIN_REPLY_DIGITS] digits) and emergency numbers.
     * Short codes are exactly the "premium"/service senders the platform warns about and must never be
     * answered automatically.
     */
    fun isReplyEligible(number: String?): Boolean {
        val normalized = normalizeNumber(number) ?: return false
        if (normalized.contains(Regex("[A-Za-z]"))) return false
        val digits = normalized.count { it.isDigit() }
        if (digits < MIN_REPLY_DIGITS) return false
        if (normalized in EMERGENCY_NUMBERS || normalized.trimStart('+') in EMERGENCY_NUMBERS) return false
        // Service numbers: 8-800 / +800 are carrier service, never a person.
        val bare = normalized.trimStart('+')
        if (bare.startsWith("8800") || bare.startsWith("800")) return false
        return true
    }

    /**
     * Normalizes a phone number to its comparable form: digits plus an optional leading `+`, with
     * spaces, dashes, parentheses and dots removed. Returns `null` when there is no digit at all.
     * Alphanumeric sender ids are kept as-is (lowercased) so they can still be matched exactly.
     */
    fun normalizeNumber(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val plus = trimmed.startsWith("+")
        val cleaned = trimmed.filter { it.isDigit() || it.isLetter() }
        if (cleaned.isEmpty()) return null
        val lower = cleaned.lowercase(Locale.ROOT)
        return if (plus) "+$lower" else lower
    }

    /** `true` when [normalized] matches any entry of [list] after [normalizeNumber] comparison. */
    fun contains(list: List<String>, normalized: String): Boolean =
        list.any { normalizeNumber(it) == normalized }

    /**
     * Parses a CSV (also accepts `;`, newlines and spaces) into a normalized, de-duplicated number
     * list, dropping blanks. No cap is applied here — [CallRulesCodec.sanitize] bounds the result.
     */
    fun parseNumberList(csv: String?): List<String> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(',', ';', '\n')
            .flatMap { it.trim().split(WHITESPACE) }
            .mapNotNull { normalizeNumber(it) }
            .distinct()
    }

    /** Renders [list] back to a `, `-joined CSV of normalized numbers (for tool output). */
    fun formatNumberList(list: List<String>): String =
        list.mapNotNull { normalizeNumber(it) }.distinct().joinToString(", ")
}
