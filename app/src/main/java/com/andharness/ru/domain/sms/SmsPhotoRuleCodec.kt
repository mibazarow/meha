package com.andharness.ru.domain.sms

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure encode/decode/sanitize for [SmsPhotoRuleState]. A corrupt/foreign/missing blob degrades to
 * the opt-in no-op defaults instead of throwing, and the prefix/cooldown/log are always bounded.
 */
object SmsPhotoRuleCodec {

    /** Hard cap on the reply prefix, so it can never carry a whole message body. */
    const val MAX_PREFIX_CHARS = 200

    /** Hard upper bound on the cooldown (24 h); `0` is allowed and disables it. */
    const val MAX_COOLDOWN_SECONDS = 86_400

    /** Hard cap on the per-number cooldown log; the most recently replied numbers are kept. */
    const val MAX_REPLY_LOG = 200

    private val json = AppJson.LenientEncodeDefaults

    private val pretty = AppJson.LenientEncodeDefaultsPretty

    /** Compact form persisted to disk. */
    fun encode(state: SmsPhotoRuleState): String = json.encodeToString(sanitize(state))

    /** Pretty form used by `sms_photo_rule_get` so a human/agent can read it. */
    fun pretty(state: SmsPhotoRuleState): String = pretty.encodeToString(sanitize(state))

    /** Decodes the persisted state; a corrupt/foreign blob degrades to the opt-in defaults. */
    fun decode(raw: String?): SmsPhotoRuleState =
        json.decodeOrNull<SmsPhotoRuleState>(raw)?.let { sanitize(it) } ?: SmsPhotoRuleState()

    /** Clamps and normalizes any input into a safe, bounded state. */
    fun sanitize(state: SmsPhotoRuleState): SmsPhotoRuleState {
        val rule = state.rule
        val boundedRule = rule.copy(
            replyTextPrefix = rule.replyTextPrefix.trim().take(MAX_PREFIX_CHARS),
            cooldownSeconds = rule.cooldownSeconds.coerceIn(0, MAX_COOLDOWN_SECONDS),
        )
        val log = state.replyLog.entries
            .asSequence()
            .mapNotNull { (number, at) ->
                val normalized = SmsPhotoRuleEngine.normalizeNumber(number) ?: return@mapNotNull null
                if (at <= 0L) return@mapNotNull null
                normalized to at
            }
            .sortedBy { it.second }
            .toList()
            .takeLast(MAX_REPLY_LOG)
        val boundedLog = LinkedHashMap<String, Long>(log.size)
        for ((number, at) in log) boundedLog[number] = at
        return SmsPhotoRuleState(rule = boundedRule, replyLog = boundedLog)
    }
}
