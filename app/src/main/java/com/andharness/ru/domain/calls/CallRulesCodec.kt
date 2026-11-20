package com.andharness.ru.domain.calls

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure encode/decode/sanitize for [CallRulesState]. A corrupt/foreign/missing blob degrades to the
 * opt-in no-op defaults instead of throwing, and every list is normalized, de-duplicated and bounded,
 * so a hand-edited file can never grow the settings or the reply log without limit.
 */
object CallRulesCodec {

    /** Hard cap on stored blocked/allowlisted numbers (each list). */
    const val MAX_NUMBERS = 200

    /** Hard cap on the auto-reply text, so it can never carry a whole message body. */
    const val MAX_REPLY_CHARS = 300

    /** Hard cap on the per-number cooldown log; the most recently replied numbers are kept. */
    const val MAX_REPLY_LOG = 200

    private val json = AppJson.LenientEncodeDefaults

    private val pretty = AppJson.LenientEncodeDefaultsPretty

    /** Compact form persisted to disk. */
    fun encode(state: CallRulesState): String = json.encodeToString(sanitize(state))

    /** Pretty form used by `call_rules_list` so a human/agent can read the rules. */
    fun pretty(state: CallRulesState): String = pretty.encodeToString(sanitize(state))

    /** Decodes the persisted state; a corrupt/foreign blob degrades to the opt-in defaults. */
    fun decode(raw: String?): CallRulesState =
        json.decodeOrNull<CallRulesState>(raw)?.let { sanitize(it) } ?: CallRulesState()

    /** Clamps and normalizes any input into a safe, bounded state. */
    fun sanitize(state: CallRulesState): CallRulesState {
        val rules = state.rules
        val boundedRules = rules.copy(
            blockedNumbers = boundedNumbers(rules.blockedNumbers),
            allowNumbers = boundedNumbers(rules.allowNumbers),
            autoReplyText = rules.autoReplyText.trim().take(MAX_REPLY_CHARS),
        )
        val log = state.autoReplyLog.entries
            .asSequence()
            .mapNotNull { (number, at) ->
                val normalized = CallRuleEngine.normalizeNumber(number) ?: return@mapNotNull null
                if (at <= 0L) return@mapNotNull null
                normalized to at
            }
            .sortedBy { it.second }
            .toList()
            .takeLast(MAX_REPLY_LOG)
        val boundedLog = LinkedHashMap<String, Long>(log.size)
        for ((number, at) in log) boundedLog[number] = at
        return CallRulesState(rules = boundedRules, autoReplyLog = boundedLog)
    }

    /** Normalizes, drops blanks, de-duplicates (order-preserving) and caps a number list. */
    fun boundedNumbers(raw: List<String>): List<String> =
        raw.asSequence()
            .mapNotNull { CallRuleEngine.normalizeNumber(it) }
            .distinct()
            .take(MAX_NUMBERS)
            .toList()
}
