package com.andharness.ru.domain.retrieval

import com.andharness.ru.domain.formatting.WHITESPACE
import com.andharness.ru.domain.formatting.collapseWhitespace

/**
 * Extracts generic graph entities (topics / projects / places / other) from a long-term memory's
 * free text and tags, mirroring how [MemoryPersonLinker] turns text into person mentions.
 *
 * Pure Kotlin (no Room/Android), **deterministic** and **bounded**: for a fixed ([text], [tags]) the
 * result is always identical, at most [MAX_ENTITIES] entries are returned, and no randomness, clocks
 * or I/O are involved. Precision is deliberately favoured over recall — a wrong edge pollutes graph
 * recall, a missing one merely loses a hint.
 *
 * Rules:
 * - Every tag (split on commas/whitespace, trimmed, at least [MIN_NAME_CHARS] chars) becomes a
 *   `topic` with [WEIGHT_TAG]. Tags are explicit, high-signal metadata.
 * - Every mid-sentence run of at least two consecutive capitalized words becomes an `other` entity
 *   with [WEIGHT_PROPER_NOUN]. Runs that begin a sentence are dropped: a capitalized first word is
 *   usually just sentence casing, not a name.
 * - Single common words are never extracted, and duplicates collapse onto the tag (higher) weight.
 *
 * Official references:
 * - Kotlin `String` (`lowercase`, `split`, `trim`, `isUpperCase`, `isLetter`):
 *   https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/
 * - Kotlin collections (`LinkedHashMap`, `putIfAbsent`, `take`):
 *   https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/
 */
object MemoryEntityExtractor {

    const val KIND_TOPIC = "topic"
    const val KIND_OTHER = "other"

    const val WEIGHT_TAG = 1.0f
    const val WEIGHT_PROPER_NOUN = 0.8f

    /** Names below this length are too ambiguous to be a useful entity. */
    const val MIN_NAME_CHARS = 2

    /** Hard cap on the number of entities returned, so graph work stays bounded. */
    const val MAX_ENTITIES = 10

    /** Cap on proper-noun runs considered from the text, before dedup/cap. */
    private const val MAX_PROPER_NOUNS = 10

    private val TAG_SPLIT = Regex("[,\\s]+")
    private val SENTENCE_END = charArrayOf('.', '!', '?', '…')

    /**
     * Returns the generic entities of the memory, tags first (strongest signal) then proper-noun
     * runs in text order, deduplicated by [ExtractedEntity.normalizedName] and capped at
     * [MAX_ENTITIES]. Blank inputs yield an empty list.
     */
    fun extract(text: String, tags: String): List<ExtractedEntity> {
        val deduped = LinkedHashMap<String, ExtractedEntity>()
        tagEntities(tags).forEach { deduped.putIfAbsent(it.normalizedName, it) }
        properNounEntities(text).forEach { deduped.putIfAbsent(it.normalizedName, it) }
        return deduped.values.take(MAX_ENTITIES)
    }

    /** Canonical form: trimmed, lower-cased, internal whitespace collapsed to single spaces. */
    fun normalize(value: String): String =
        collapseWhitespace(value.lowercase())

    private fun tagEntities(tags: String): List<ExtractedEntity> {
        if (tags.isBlank()) return emptyList()
        val out = ArrayList<ExtractedEntity>()
        for (raw in tags.split(TAG_SPLIT)) {
            val name = raw.trim()
            if (name.length < MIN_NAME_CHARS) continue
            out += ExtractedEntity(name, KIND_TOPIC, WEIGHT_TAG)
            if (out.size >= MAX_ENTITIES) break
        }
        return out
    }

    private fun properNounEntities(text: String): List<ExtractedEntity> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<ExtractedEntity>()
        for (line in text.split('\n')) {
            if (out.size >= MAX_PROPER_NOUNS) break
            val tokens = line.split(WHITESPACE).filter { it.isNotEmpty() }
            var i = 0
            // The first token of every line and every token after sentence punctuation is
            // sentence-initial; a capitalized run starting there is treated as noise.
            var sentenceStart = true
            while (i < tokens.size && out.size < MAX_PROPER_NOUNS) {
                if (isCapitalizedWord(tokens[i])) {
                    var j = i
                    while (j < tokens.size && isCapitalizedWord(tokens[j])) j++
                    if (j - i >= 2 && !sentenceStart) {
                        val name = (i until j).joinToString(" ") { wordOf(tokens[it]) }
                        out += ExtractedEntity(name, KIND_OTHER, WEIGHT_PROPER_NOUN)
                    }
                    sentenceStart = endsSentence(tokens[j - 1])
                    i = j
                } else {
                    sentenceStart = endsSentence(tokens[i])
                    i++
                }
            }
        }
        return out
    }

    /** `true` when the token, with surrounding punctuation stripped, starts with an upper-case letter. */
    private fun isCapitalizedWord(token: String): Boolean {
        val word = wordOf(token)
        if (word.length < MIN_NAME_CHARS) return false
        val first = word[0]
        return first.isLetter() && first.isUpperCase()
    }

    /** Strips punctuation around a token, e.g. «(Иван,» → «Иван». */
    private fun wordOf(token: String): String =
        token.trim { !it.isLetter() && it != '-' && it != '\'' && it != '’' }

    private fun endsSentence(token: String): Boolean {
        val trimmed = token.trimEnd()
        if (trimmed.isEmpty()) return false
        return trimmed.last() in SENTENCE_END
    }
}
