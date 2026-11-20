package com.andharness.ru.domain.style

import java.util.Locale

/**
 * Pure, deterministic per-message style measurements. No Android, no model calls, no word list for
 * profanity (that arrives separately), so the analyzer is fully JVM-testable and cheap enough to run
 * on every user message.
 *
 * References:
 * - `String.codePoints()` for surrogate-pair-safe emoji scanning:
 *   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#codePoints()
 * - `Character.getType(int)` / `Character.OTHER_SYMBOL`:
 *   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Character.html#getType(int)
 */
object StyleAnalyzer {

    /** Messages up to this length count as "short" for the brevity trait. */
    const val SHORT_MESSAGE_CHARS = 60

    /** A message without final punctuation is only "relaxed" once it is at least this long. */
    const val NO_FINAL_PUNCTUATION_MIN_CHARS = 25

    /** A lowercase first letter only signals informality on a message of at least this length. */
    const val LOWERCASE_START_MIN_CHARS = 12

    private val TOKEN = Regex("[\\p{L}]+")

    private val TY_PRONOUNS = setOf(
        "ты", "тебя", "тебе", "тобой", "тобою", "твой", "твоя", "твоё", "твое", "твои",
        "твоих", "твоим", "твоими", "твоему", "твоей", "твоего", "твою",
    )

    private val VY_PRONOUNS = setOf(
        "вы", "вас", "вам", "вами", "ваш", "ваша", "ваше", "ваши", "ваших", "вашим", "вашими",
        "вашему", "вашей", "вашего", "вашу",
    )

    private val SLANG = setOf(
        "норм", "короче", "хз", "имхо", "лол", "кек", "зашквар", "жиза", "кринж", "топ", "база",
        "чел", "чувак", "бро", "ок", "оки", "ага", "ясно", "понял", "поняла", "походу", "вроде",
        "тип", "типо",
    )

    private val DIRECTIVE_VERBS = setOf(
        "сделай", "сделайте", "дай", "дайте", "открой", "откройте", "покажи", "покажите",
        "включи", "включите", "выключи", "выключите", "поставь", "поставьте", "найди",
        "напиши", "напишите", "позвони", "позвоните", "отправь", "отправьте", "удали",
        "удалите", "создай", "создайте", "переключи", "переключите", "скачай", "скачайте",
        "проверь", "проверьте", "запусти", "запустите", "закрой", "закройте",
    )

    private val SOFT_IMPERATIVES = setOf("давай", "давайте")

    private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', '…')

    /** Measures [text]; [profanityHits] is supplied by the profanity lexicon. */
    fun analyze(text: String, profanityHits: Int = 0): StyleSignal {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return StyleSignal.EMPTY

        val tokens = tokens(trimmed)
        return StyleSignal(
            hasContent = true,
            length = trimmed.length,
            address = address(tokens),
            hasEmoji = hasEmoji(trimmed),
            hasSlang = tokens.any { it in SLANG },
            profanityHits = profanityHits.coerceAtLeast(0),
            informalPunctuation = informalPunctuation(trimmed),
            isDirective = isDirective(tokens),
        )
    }

    private fun tokens(text: String): List<String> =
        TOKEN.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()

    private fun address(tokens: List<String>): AddressForm? {
        val ty = tokens.any { it in TY_PRONOUNS }
        val vy = tokens.any { it in VY_PRONOUNS }
        return when {
            ty && !vy -> AddressForm.TY
            vy && !ty -> AddressForm.VY
            else -> null
        }
    }

    private fun hasEmoji(text: String): Boolean {
        val codePoints = text.codePoints().iterator()
        while (codePoints.hasNext()) {
            val cp = codePoints.nextInt()
            // ©, ® and ™ are OTHER_SYMBOL but are not emoji for this purpose.
            if (cp == 0x00A9 || cp == 0x00AE || cp == 0x2122) continue
            if (cp == 0xFE0F) return true
            if (cp in 0x1F300..0x1FAFF || cp in 0x1F1E6..0x1F1FF) return true
            if (cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF) return true
            if (Character.getType(cp) == Character.OTHER_SYMBOL.toInt()) return true
        }
        return false
    }

    private fun informalPunctuation(text: String): Boolean {
        if (text.contains("!!")) return true
        if (text.contains("...") || text.contains('…')) return true
        val first = text.firstOrNull()
        if (first != null && first.isLetter() && first.isLowerCase() &&
            text.length >= LOWERCASE_START_MIN_CHARS
        ) {
            return true
        }
        val endsWithPunctuation = text.lastOrNull() in TERMINAL_PUNCTUATION
        return !endsWithPunctuation && text.length >= NO_FINAL_PUNCTUATION_MIN_CHARS
    }

    private fun isDirective(tokens: List<String>): Boolean {
        if (tokens.any { it in SOFT_IMPERATIVES }) return true
        return tokens.take(3).any { it in DIRECTIVE_VERBS }
    }
}
