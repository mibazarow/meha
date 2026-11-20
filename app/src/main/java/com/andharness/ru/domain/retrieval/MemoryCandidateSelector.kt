package com.andharness.ru.domain.retrieval

/**
 * Решение о выборе кандидатов памяти: использовать ли FTS-шортлист или прежний
 * полный скан. Порог намеренно оставляет маленькие базы на старом пути, поэтому поведение (и все
 * существующие тесты) не меняется; FTS включается только когда полный скан реально дорог.
 *
 * Чистая функция без Room/Android — тестируется на JVM.
 */
object MemoryCandidateSelector {

    /**
     * Порог: при количестве строк памяти строго больше этого значения recall сначала пробует FTS.
     * 200 выбрано как заведомо «больше, чем токенизация за один ход» граница; на <= 200 строках
     * полный скан дешёв и даёт ровно прежнее ранжирование.
     */
    const val FTS_THRESHOLD = 200

    /** true — таблица памяти достаточно большая, чтобы пробовать FTS-шортлист. */
    fun shouldUseFts(memoryCount: Int): Boolean = memoryCount > FTS_THRESHOLD

    /**
     * true — нужно вернуться к полному скану: либо база мала (<= [FTS_THRESHOLD]), либо FTS не дал
     * ни одного кандидата (например, совпадение только по синониму/иной форме слова).
     */
    fun shouldFallBackToFullScan(memoryCount: Int, ftsCandidateCount: Int): Boolean =
        !shouldUseFts(memoryCount) || ftsCandidateCount <= 0
}
