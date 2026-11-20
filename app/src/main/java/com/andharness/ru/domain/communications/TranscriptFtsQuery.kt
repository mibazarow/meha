package com.andharness.ru.domain.communications

/**
 * Builds a safe SQLite FTS4 `MATCH` expression from the free-text query of transcript search.
 *
 * User input cannot be dropped into `MATCH` verbatim: characters like `"`, `*`, `-` or bare words
 * such as `NOT`/`OR`/`NEAR` are FTS operators and a malformed expression makes the whole query
 * throw. Every word of the query is therefore stripped to letters/digits, wrapped in double quotes
 * (so it is a literal), and the terms are joined with `OR` — any one match is enough. Duplicates are
 * removed and a query with no usable word yields the empty string, which callers treat as "no FTS
 * candidates".
 *
 * The tokenizer is `unicode61` (see
 * [com.andharness.ru.data.db.entity.TranscriptFtsEntity]), so Cyrillic words are matched
 * case-insensitively; `\\p{L}`/`\\p{N}` keep them intact.
 *
 * Pure Kotlin (no Room/Android), so it is unit-testable on the JVM.
 *
 * Reference: https://www.sqlite.org/fts3.html
 */
object TranscriptFtsQuery {

    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** Returns the `MATCH` expression, or `""` when [query] has no usable term. */
    fun build(query: String): String {
        val terms = LinkedHashSet<String>()
        for (word in query.split(WORD_SPLIT)) {
            if (word.isNotEmpty()) terms += quoted(word)
        }
        return terms.joinToString(" OR ")
    }

    private fun quoted(term: String) = "\"$term\""
}
