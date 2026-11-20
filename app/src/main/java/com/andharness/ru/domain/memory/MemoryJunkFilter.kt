package com.andharness.ru.domain.memory

/**
 * Cheap, LLM-free gate that stops junk from becoming a long-term memory (BUGS.md M1).
 *
 * Why a phone needs this more than a desktop chat: speech input and two-word replies in a car or on
 * the street produce far more fragments than typing does, and a junk fact that reaches `notes` is
 * injected into every later prompt — it lives there until someone deletes it.
 *
 * Two independent checks, ported from Оборбот's `crates/core/src/memory/entities.rs`:
 * - [isLowValue] rejects acknowledgements and fillers («ок», «ага», «спасибо», «лол»), and anything
 *   that is not really words;
 * - [isJunkFact] rejects narration ABOUT the conversation instead of a fact IN it — «говорящий
 *   сказал…», «участник чата…», «…, упомянутый говорящим» — plus anything shorter than
 *   [MIN_FACT_CHARS].
 *
 * Pure Kotlin, deterministic, unit-tested. Both lists are plain constants next to the code so they
 * can be extended from real logs without touching the logic.
 */
object MemoryJunkFilter {

    /** A fact shorter than this is a fragment, not a fact. */
    const val MIN_FACT_CHARS = 8

    /** How much of the start of the text is inspected for a narration prefix. */
    private const val PREFIX_SCAN_CHARS = 40

    /**
     * Whole-utterance fillers. Compared after normalisation (lower-case, punctuation stripped), so
     * «Ок!» and «ок…» are the same entry. Only EXACT matches are rejected: «ок, тогда в четверг» is
     * a real fact and must survive.
     */
    private val LOW_VALUE = setOf(
        "ку", "ок", "окей", "ok", "okay", "ага", "угу", "да", "нет", "неа", "ясно", "понял", "поняла",
        "понятно", "спс", "спасибо", "благодарю", "лол", "ахах", "ахаха", "хех", "ха", "хаха",
        "привет", "здравствуйте", "здарова", "пока", "до свидания", "доброе утро", "добрый день",
        "добрый вечер", "спокойной ночи", "угу-угу", "хм", "мм", "эм", "ну", "вот", "ладно", "хорошо",
        "yes", "no", "hi", "hello", "thanks", "thank you", "lol", "hmm", "bye", "good morning",
    )

    /**
     * Narration prefixes: the text describes who spoke instead of what is true. Matched as a
     * case-insensitive PREFIX of the normalised text, so «Говорящий сказал, что…» is caught while a
     * fact that merely contains the word «говорил» later is not.
     *
     * «Пользователь …» is deliberately NOT in this list even though Оборбот has it: it is the
     * subject of this app's own fact format («Пользователь любит кофе»), so rejecting it would
     * throw away real memories.
     */
    private val JUNK_PREFIXES = listOf(
        "говорящ", "говорящий", "упомянут", "автор сообщения",
        "участник чата", "написал сообщение", "написала сообщение", "сказал, что", "сказала, что",
        "в сообщении", "в этом сообщении", "в переписке", "speaker", "the user said", "mentioned",
        "the message says", "this message", "participant",
    )

    /** Narration tails: a fact fragment that only exists as an aside about the speaker. */
    private val JUNK_SUFFIXES = listOf(
        ", упомянутый говорящим", ", упомянутая говорящим", ", упомянутое говорящим",
        " упомянутый говорящим", " mentioned by the speaker", " mentioned by the user",
    )

    /** True when [text] carries no information: an acknowledgement, a filler or punctuation only. */
    fun isLowValue(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return true
        if (normalized in LOW_VALUE) return true
        // A repeated filler («ну-ну», «ок ок», «да да») is the same acknowledgement twice.
        val parts = normalized.split(' ')
        if (parts.size > 1 && parts.all { it in LOW_VALUE }) return true
        // A single repeated letter/syllable («ааа») is not a fact either.
        if (normalized.replace(" ", "").toSet().size <= 1) return true
        return normalized.none { it.isLetterOrDigit() }
    }

    /** True when [text] narrates the conversation instead of stating a fact, or is too short. */
    fun isJunkFact(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < MIN_FACT_CHARS) return true
        val normalized = normalize(trimmed)
        if (normalized.isEmpty()) return true
        val head = normalized.take(PREFIX_SCAN_CHARS)
        if (JUNK_PREFIXES.any { head.startsWith(it) }) return true
        val tail = normalized.takeLast(PREFIX_SCAN_CHARS)
        if (JUNK_SUFFIXES.any { tail.endsWith(it) }) return true
        return false
    }

    /** Single gate the write path calls: `false` means «do not store this as a memory». */
    fun shouldStore(text: String): Boolean =
        text.isNotBlank() && !isLowValue(text) && !isJunkFact(text)

    /** Lower-case, punctuation → spaces, whitespace collapsed. Digits and letters survive. */
    private fun normalize(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .trim()
            .replace(Regex("\\s+"), " ")
}
