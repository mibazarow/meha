package com.andharness.ru.domain.style

import java.util.Locale

/**
 * Counts Russian profanity for style learning and separates it from language that must never be
 * mirrored.
 *
 * Three classes:
 * - **mild** expressive swearing (used as an intensifier or joke) — eligible for mirroring;
 * - **insults** directed at a person — never mirrored; also suppress the mild count for that
 *   message, so an angry argument does not train the assistant to swear;
 * - **slurs** — never mirrored and always suppress the message.
 *
 * Matching is deliberately conservative: roots must start at a word boundary, known false positives
 * («хулиган», «бляха») are removed first, and common obfuscations (`х*й`, `б л я`, Latin lookalikes)
 * are normalized. The lexicon is a guardrail, not a complete dictionary; the prompt adds the final
 * behavioral limits.
 */
object ProfanityLexicon {

    /** Per-message classification. */
    data class Signal(
        val mild: Boolean,
        val insult: Boolean,
        val slur: Boolean,
    ) {
        /** `true` when the message contains language that must never be mirrored. */
        val isHostile: Boolean get() = insult || slur

        /** `1` only for a clean message with mild swearing; `0` otherwise. */
        val styleHits: Int get() = if (mild && !isHostile) 1 else 0
    }

    private val CLEAN = Signal(mild = false, insult = false, slur = false)

    private val TOKEN = Regex("[\\p{L}]+")

    private val FALSE_POSITIVES = setOf(
        "хулиган", "хулиганы", "хулигана", "хулиганка", "хулиганить", "хулиганство",
        "бляха", "бляхи", "бляшка", "бляшки",
    )

    /** Latin letters and digits commonly used to disguise Cyrillic roots. */
    private val LOOKALIKES = mapOf(
        'a' to 'а', 'b' to 'б', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'k' to 'к', 'm' to 'м',
        'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у',
        '0' to 'о', '1' to 'и', '3' to 'з', '4' to 'ч', '6' to 'б',
    )

    /**
     * Mild expressive roots. Bare `еб` is intentionally absent: it would match «себя», so only
     * prefixed forms (`заеб`, `наеб`, `уеб`, …) and explicit conjugations are listed.
     */
    private val MILD_ROOTS = listOf(
        "хуй", "хуя", "хуе", "хуё", "хуи", "хуйн",
        "нахуй", "похуй", "нихуя", "охуе", "охуи",
        "пизд",
        "бля", "блят", "блядь",
        "ебат", "ебал", "ебуч", "ебет", "ебн",
        "заеб", "наеб", "отъеб", "уеб", "съеб", "выеб", "поеб", "проеб", "разъеб", "подъеб",
        "долбоеб",
        "сука", "суч",
    )

    /** Person-directed insults. Never mirrored, and they suppress mild counting. */
    private val INSULT_ROOTS = listOf(
        "мудак", "мудил", "гандон", "долбо", "дебил", "идиот", "кретин", "урод", "тварь",
        "мразь", "шлюх", "блядин", "сволоч", "подонок", "мерзав", "тупиц", "тупой",
        "пидор", "пидар", "педик",
    )

    private val INSULT_WORDS = setOf(
        "лох", "лохи", "лоха", "чмо", "дурак", "дура", "дураки", "дурочка",
    )

    /** Hard slurs matched as whole words only, to avoid false positives like «жидкость». */
    private val SLUR_WORDS = setOf(
        "жид", "жиды", "жида", "жидов",
        "хач", "хачи", "хача", "хачей",
        "чурка", "чурки", "чурок",
        "ниггер", "ниггеры", "ниггера", "нигер", "нигеры", "нигера",
        "пидор", "пидоры", "пидорас", "пидорасы", "педик", "педики",
        "даун", "дауны", "дауна",
    )

    private fun patterns(roots: List<String>): List<Regex> = roots.map { root ->
        val body = root.map { Regex.escape(it.toString()) }.joinToString("[^\\p{L}]{0,2}")
        Regex("(?<![\\p{L}])$body")
    }

    /**
     * Forms with a dropped vowel (`х*й`, `пздц`) cannot be matched by a stem with every letter, so
     * they get their own conservative patterns. Separators are allowed between every part.
     */
    private val MILD_OBFUSCATED = listOf(
        Regex("(?<![\\p{L}])х[^\\p{L}]{0,2}(у)?[^\\p{L}]{0,2}[йяеё]"),
        Regex("(?<![\\p{L}])п[^\\p{L}]{0,2}(и|1)?[^\\p{L}]{0,2}з[^\\p{L}]{0,2}д"),
    )

    private val MILD_PATTERNS = patterns(MILD_ROOTS)
    private val INSULT_PATTERNS = patterns(INSULT_ROOTS)

    /** Classifies [text]. Never throws; blank text is clean. */
    fun analyze(text: String): Signal {
        if (text.isBlank()) return CLEAN
        val normalized = normalize(text)
        val tokens = TOKEN.findAll(normalized).map { it.value }.toSet()
        if (tokens.isEmpty()) return CLEAN

        val slur = tokens.any { it in SLUR_WORDS }
        val insult = tokens.any { it in INSULT_WORDS } || INSULT_PATTERNS.any { it.containsMatchIn(normalized) }
        val mild = MILD_PATTERNS.any { it.containsMatchIn(normalized) } ||
            MILD_OBFUSCATED.any { it.containsMatchIn(normalized) }
        return Signal(mild = mild, insult = insult, slur = slur)
    }

    /** Style-eligible hits: `1` for clean mild swearing, `0` for clean or hostile text. */
    fun count(text: String): Int = analyze(text).styleHits

    /** `true` when [text] contains an insult or a slur (never eligible for mirroring). */
    fun containsHardLanguage(text: String): Boolean = analyze(text).isHostile

    private fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            sb.append(LOOKALIKES[ch] ?: ch)
        }
        var normalized = sb.toString()
        for (word in FALSE_POSITIVES) {
            normalized = normalized.replace(word, " ")
        }
        return normalized
    }
}
