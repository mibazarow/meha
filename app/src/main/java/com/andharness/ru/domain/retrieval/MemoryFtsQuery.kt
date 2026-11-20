package com.andharness.ru.domain.retrieval

/**
 * Строит безопасное выражение SQLite FTS4 `MATCH` из свободного запроса recall.
 *
 * Правила:
 * - базовые слова запроса берутся из [TextRetriever.tokenize], поэтому наследуются стоп-слова,
 *   усечение суффиксов и — главное — расширение синонимов («налоговая» -> «фнс»);
 * - из префиксных токенов токенизатора и «сырых» слов длиной >= [MIN_PREFIX_LENGTH] добавляются
 *   префиксные запросы (`"нало"*`), чтобы FTS находил формы слова без морфологии в самом SQLite;
 * - каждый терм обёрнут в кавычки, поэтому `not`/`near`/`or` не становятся операторами FTS;
 * - термы объединяются через `OR` (достаточно совпадения одного), дубликаты убираются.
 *
 * Пустая строка означает «FTS не дал ни одного терма» — вызывающий обязан откатиться на полный
 * скан. Чистая Kotlin-функция (без Room/Android), поэтому тестируется на JVM.
 *
 * Reference: https://www.sqlite.org/fts3.html
 */
object MemoryFtsQuery {

    /** Слова короче этого не дают префиксного терма: слишком много ложных кандидатов. */
    const val MIN_PREFIX_LENGTH = 4

    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun build(query: String): String {
        val terms = LinkedHashSet<String>()
        for (token in TextRetriever.tokenize(query)) {
            when {
                token.startsWith(TextRetriever.PREFIX_MARK) -> {
                    val prefix = token.removePrefix(TextRetriever.PREFIX_MARK)
                    if (prefix.length >= MIN_PREFIX_LENGTH) terms += quoted(prefix) + "*"
                }
                token.isNotBlank() -> terms += quoted(token)
            }
        }
        // «Сырые» слова: страхуют префиксный терм, даже если стем/синоним токенизатора дал иное.
        for (word in query.split(WORD_SPLIT)) {
            val lower = word.lowercase()
            if (lower.length >= MIN_PREFIX_LENGTH) {
                terms += quoted(lower.take(MIN_PREFIX_LENGTH)) + "*"
            }
        }
        return terms.joinToString(" OR ")
    }

    private fun quoted(term: String) = "\"$term\""
}
