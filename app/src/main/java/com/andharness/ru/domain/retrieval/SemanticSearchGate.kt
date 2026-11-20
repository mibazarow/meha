package com.andharness.ru.domain.retrieval

/**
 * Чистое решение «нужно ли считать эмбеддинг для этого текста». Вынесено из recall-пути и
 * фонового воркера, чтобы гейтинг (`semanticSearchEnabled` + непустой текст) был одинаковым в обоих
 * местах и покрывался JVM-тестом: когда переключатель выключен или текст пуст, встроенная
 * модель не загружается и никакой работы не выполняется.
 *
 * Reference (встроенная модель): `com.andharness.ru.data.embedding.LocalEmbedder`.
 */
object SemanticSearchGate {

    /** `true` только если семантика включена и есть что эмбеддить. */
    fun shouldEmbed(enabled: Boolean, text: String): Boolean = enabled && text.isNotBlank()
}
