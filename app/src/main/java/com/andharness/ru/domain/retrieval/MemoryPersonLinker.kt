package com.andharness.ru.domain.retrieval

import com.andharness.ru.domain.person.PersonResolver

/**
 * Links a long-term memory's free text to the persons it mentions, so graph recall can traverse
 * "memory ↔ person" edges (which facts belong to «какой Серёга»).
 *
 * Pure Kotlin (no Room/Android), **deterministic** and **bounded**: for a fixed
 * ([memoryText], [candidates]) the result is always identical, at most [MAX_LINKS] entries are
 * returned, and no randomness, clocks or I/O are involved. The whole algorithm is therefore
 * unit-testable on the JVM.
 *
 * Matching is done on `PersonResolver.canonicalName` (lower-case, Cyrillic → Latin transliteration,
 * punctuation collapsed to single spaces) and works on **whole tokens**: a name is found only as a
 * contiguous token subsequence of the memory tokens, never as a substring inside a larger token
 * («иван» must not match «иваново»). Each candidate name token additionally tolerates Russian case
 * endings via [foldTrailingVowels] (e.g. canonical «serega» matches «seregoy»), while the folded
 * stem must stay at least [MIN_STEM_CHARS] long — this keeps «ivan» from matching «ivanovo», whose
 * stem differs.
 *
 * Official references:
 * - Kotlin `String` (`lowercase`, `split`, `substring`, `replace`):
 *   https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/
 * - Kotlin collections (`sortedWith`, `compareBy`, `take`):
 *   https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/
 */
object MemoryPersonLinker {

    /**
     * A name whose total canonical length is below this is ignored: it is too short to identify a
     * person without false positives (e.g. «Ян» → `yan`).
     */
    const val MIN_NAME_CHARS = 4

    /** Hard cap on the number of links returned, so recall work stays bounded. */
    const val MAX_LINKS = 10

    private const val WEIGHT_FULL_NAME = 1.0f
    private const val WEIGHT_LONG_SINGLE = 0.8f
    private const val WEIGHT_SHORT_SINGLE = 0.6f

    /** A folded stem shorter than this is not used for matching (avoids degenerate matches). */
    private const val MIN_STEM_CHARS = 3

    /** Latin vowels produced by `PersonResolver.canonicalName`, used by [foldTrailingVowels]. */
    private const val VOWELS = "aeiouy"

    /**
     * Returns the persons mentioned in [memoryText], strongest link first.
     *
     * One [PersonLink] per `personId` (the max weight over that person's names wins); persons with
     * no matching name are dropped. The result is sorted by weight descending, then `personId`
     * ascending, and capped at [MAX_LINKS]. Blank text or an empty candidate list yields an empty
     * list.
     */
    fun link(memoryText: String, candidates: List<PersonNameCandidate>): List<PersonLink> {
        if (memoryText.isBlank() || candidates.isEmpty()) return emptyList()
        val memoryTokens = tokensOf(PersonResolver.canonicalName(memoryText))
        if (memoryTokens.isEmpty()) return emptyList()

        val best = HashMap<Long, Float>()
        for (candidate in candidates) {
            var weight = 0f
            for (name in candidate.names) {
                val nameWeight = weightOf(name, memoryTokens)
                if (nameWeight > weight) weight = nameWeight
            }
            if (weight > 0f) {
                val current = best[candidate.personId]
                if (current == null || weight > current) best[candidate.personId] = weight
            }
        }
        if (best.isEmpty()) return emptyList()

        return best.entries
            .map { PersonLink(it.key, it.value) }
            .sortedWith(compareByDescending<PersonLink> { it.weight }.thenBy { it.personId })
            .take(MAX_LINKS)
    }

    /**
     * Best weight of one candidate [name] against [memoryTokens], or `0f` when the name is too
     * short/blank or is not present as a contiguous token subsequence of the memory.
     */
    private fun weightOf(name: String, memoryTokens: List<String>): Float {
        val canonical = PersonResolver.canonicalName(name)
        if (canonical.isEmpty()) return 0f
        if (canonical.replace(" ", "").length < MIN_NAME_CHARS) return 0f

        val nameTokens = tokensOf(canonical)
        if (nameTokens.isEmpty()) return 0f
        if (!containsSubsequence(memoryTokens, nameTokens)) return 0f

        return when {
            nameTokens.size >= 2 -> WEIGHT_FULL_NAME
            nameTokens[0].length >= 5 -> WEIGHT_LONG_SINGLE
            else -> WEIGHT_SHORT_SINGLE
        }
    }

    /** `true` when [nameTokens] appear contiguously, in order, inside [memoryTokens]. */
    private fun containsSubsequence(memoryTokens: List<String>, nameTokens: List<String>): Boolean {
        if (nameTokens.size > memoryTokens.size) return false
        val lastStart = memoryTokens.size - nameTokens.size
        for (start in 0..lastStart) {
            var matched = true
            for (i in nameTokens.indices) {
                if (!tokenMatches(memoryTokens[start + i], nameTokens[i])) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    /**
     * Token equality after canonicalization, tolerant to Russian case endings: two tokens match
     * when they are identical, or when their [foldTrailingVowels] stems are equal and at least
     * [MIN_STEM_CHARS] long. This is token-boundary matching (never substring): «serega» matches
     * «seregoy», but «ivan» does not match «ivanovo».
     */
    private fun tokenMatches(memoryToken: String, nameToken: String): Boolean {
        if (memoryToken == nameToken) return true
        val memoryStem = foldTrailingVowels(memoryToken)
        val nameStem = foldTrailingVowels(nameToken)
        return memoryStem == nameStem && memoryStem.length >= MIN_STEM_CHARS
    }

    /**
     * Drops trailing Latin vowels (the endings Russian inflection adds after a stem), e.g.
     * «seregoy» → «sereg», «serega» → «sereg», «ivanovo» → «ivanov».
     */
    private fun foldTrailingVowels(token: String): String {
        var end = token.length
        while (end > 0 && token[end - 1] in VOWELS) end--
        return token.substring(0, end)
    }

    /** Canonical string → non-empty tokens; `PersonResolver.canonicalName` already collapses spaces. */
    private fun tokensOf(canonical: String): List<String> =
        canonical.split(' ').filter { it.isNotEmpty() }
}
