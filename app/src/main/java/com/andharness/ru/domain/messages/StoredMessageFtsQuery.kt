package com.andharness.ru.domain.messages

/**
 * Builds a safe SQLite FTS4 `MATCH` expression from the free-text query of message search (clone of
 * [com.andharness.ru.domain.communications.TranscriptFtsQuery]).
 *
 * User input cannot be dropped into `MATCH` verbatim: characters like `"`, `*`, `-` or bare words
 * such as `NOT`/`OR`/`NEAR` are FTS operators and a malformed expression makes the whole query throw.
 * Every word is stripped to letters/digits, wrapped in double quotes (so it is a literal), and the
 * terms are joined with `OR`. Duplicates are removed and a query with no usable word yields `""`,
 * which callers treat as "no FTS candidates".
 *
 * The tokenizer is `unicode61` (see
 * [com.andharness.ru.data.db.entity.StoredMessageFtsEntity]), so Cyrillic words match
 * case-insensitively.
 *
 * Pure Kotlin (no Room/Android), so it is unit-testable on the JVM.
 *
 * Reference: https://www.sqlite.org/fts3.html
 */
object StoredMessageFtsQuery {

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
