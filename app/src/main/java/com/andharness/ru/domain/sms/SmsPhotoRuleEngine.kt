package com.andharness.ru.domain.sms

import java.util.Locale

/**
 * Pure policy for the trusted-SMS photo report. All functions are side-effect free.
 *
 * Precedence inside [decide] (most specific first):
 * 1. rule disabled → IGNORE;
 * 2. sender not trusted → IGNORE;
 * 3. sender is a service/short/emergency number → IGNORE;
 * 4. body is not a photo command → IGNORE;
 * 5. per-number cooldown active → IGNORE;
 * 6. otherwise → REPORT.
 */
object SmsPhotoRuleEngine {

    /**
     * RU/EN photo commands. Matching is case-insensitive and punctuation-insensitive: the body is
     * normalized and each phrase is then tested as a substring, so "Сделай фото!" and
     * "take a photo please" both match.
     */
    val PHOTO_COMMANDS: List<String> = listOf(
        "что на фото",
        "сделай фото",
        "сделай снимок",
        "take a photo",
        "what do you see",
        "фото",
        "снимок",
        "photo",
        "picture",
    )

    /** Digits a real dialable number needs before it may receive an automatic reply. */
    const val MIN_REPLY_DIGITS = 7

    private val EMERGENCY_NUMBERS = setOf(
        "112", "911", "000",
        "100", "101", "102", "103", "104", "105", "106", "107", "108",
    )

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** `true` when [body] contains any photo command (case-insensitive, punctuation-insensitive). */
    fun matchesPhotoCommand(body: String?): Boolean {
        val normalized = normalizeText(body)
        if (normalized.isEmpty()) return false
        return PHOTO_COMMANDS.any { normalized.contains(normalizeText(it)) }
    }

    /** The full decision for one inbound SMS. Never throws. */
    fun decide(
        rule: SmsPhotoRule,
        senderTrusted: Boolean,
        senderEligible: Boolean,
        body: String?,
        coolingDown: Boolean,
    ): SmsPhotoPlan = when {
        !rule.enabled -> SmsPhotoPlan(SmsPhotoDecision.IGNORE, "правило выключено (enabled=false)")
        !senderTrusted -> SmsPhotoPlan(SmsPhotoDecision.IGNORE, "номер не в списке доверенных")
        !senderEligible -> SmsPhotoPlan(SmsPhotoDecision.IGNORE, "служебный/короткий номер — ответ запрещён")
        !matchesPhotoCommand(body) -> SmsPhotoPlan(SmsPhotoDecision.IGNORE, "нет команды фото")
        coolingDown -> SmsPhotoPlan(SmsPhotoDecision.IGNORE, "кулдаун на этот номер ещё не истёк")
        else -> SmsPhotoPlan(SmsPhotoDecision.REPORT, "доверенный номер, команда фото принята")
    }

    /**
     * Safety predicate: whether [number] may receive an automatic reply at all. Rejects blank/
     * alphanumeric senders, short codes/service numbers (fewer than [MIN_REPLY_DIGITS] digits) and
     * emergency numbers. The trusted-source match is applied separately by [decide].
     */
    fun isEligibleSender(number: String?): Boolean {
        val normalized = normalizeNumber(number) ?: return false
        if (normalized.contains(Regex("[A-Za-z]"))) return false
        val digits = normalized.count { it.isDigit() }
        if (digits < MIN_REPLY_DIGITS) return false
        if (normalized in EMERGENCY_NUMBERS || normalized.trimStart('+') in EMERGENCY_NUMBERS) return false
        val bare = normalized.trimStart('+')
        if (bare.startsWith("8800") || bare.startsWith("800")) return false
        return true
    }

    /**
     * Normalizes a phone number to its comparable form: digits plus an optional leading `+`, with
     * spaces, dashes, parentheses and dots removed. Returns `null` when there is no digit at all.
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

    /** Builds the SMS reply text: the rule prefix plus the analysis (never blank). */
    fun replyText(prefix: String, analysis: String): String {
        val body = analysis.trim()
        val safeBody = body.ifBlank { "не удалось получить описание" }
        return (prefix.trim() + " " + safeBody).trim()
    }

    private fun normalizeText(raw: String?): String =
        raw?.trim()?.lowercase(Locale.ROOT)?.replace(NON_WORD, " ")?.trim().orEmpty()
}
