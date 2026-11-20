package com.andharness.ru.data.assist

import com.andharness.ru.data.db.dao.AssistDao
import com.andharness.ru.data.db.dao.NoteFtsDao
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.domain.retrieval.MemoryCandidateSelector
import com.andharness.ru.domain.retrieval.MemoryFtsQuery

/**
 * Selects the candidate pool for ranked memory recall. When the memory table is large it uses the
 * cheap FTS shortlist; on a small table, an empty/failed FTS result or a missing index it falls back
 * to the previous bounded full scan, so behaviour on small data is unchanged. It also appends the
 * actual (non-superseded) opinions to the pool.
 *
 * Constructor-injected; the kind strings are passed in so the facade keeps owning the schema labels.
 */
internal class MemoryCandidateLoader(
    private val dao: AssistDao,
    private val noteFtsDao: NoteFtsDao?,
    private val kindMemory: String,
    private val memoryScanLimit: Int
) {

    /**
     * Candidates for `searchMemoriesRanked`, opinions included (superseded ones filtered out).
     *
     * [preferFullScan] is set when semantic ranking is enabled: the FTS shortlist only contains
     * LEXICALLY matching rows, so a paraphrase with no literal overlap could never be re-ranked by
     * cosine. A bounded full scan gives the semantic layer every candidate it needs (the scan is
     * still capped by [memoryScanLimit]).
     */
    suspend fun candidates(
        query: String,
        limit: Int,
        preferFullScan: Boolean = false
    ): List<NoteEntity> =
        includeActualOpinions(if (preferFullScan) fullScan() else load(query, limit), limit)

    /** Прежний отбор кандидатов памяти (FTS-шортлист либо полный скан) — без мнений. */
    private suspend fun load(query: String, limit: Int): List<NoteEntity> {
        val fts = noteFtsDao ?: return fullScan()
        val total = dao.countByKind(kindMemory)
        // Малая таблица: полный скан дёшев и даёт ровно прежнее ранжирование (поведение неизменно).
        if (!MemoryCandidateSelector.shouldUseFts(total)) return fullScan()
        val matchQuery = MemoryFtsQuery.build(query)
        if (matchQuery.isBlank()) return fullScan()
        val ids = runCatching { fts.searchCandidateIds(matchQuery, limit) }.getOrDefault(emptyList())
        // Пустой (или сбойный) FTS-результат: совпадение только по синониму/иной форме — откат.
        if (MemoryCandidateSelector.shouldFallBackToFullScan(total, ids.size)) return fullScan()
        return fts.listNotesByIds(kindMemory, ids)
    }

    /**
     * Добавляет к кандидатам recall актуальные мнения (`kind = "opinion"`) и убирает вытесненные
     * (`supersedesId` которых указывает на них) — они остаются в БД для истории, но не ранжируются.
     * При пустых мнениях/вытеснениях возвращает исходный список без изменений, поэтому поведение
     * прежних данных не меняется.
     */
    private suspend fun includeActualOpinions(
        memories: List<NoteEntity>,
        limit: Int
    ): List<NoteEntity> {
        val opinions = runCatching { dao.listOpinions(limit) }.getOrDefault(emptyList())
        val retired = runCatching { dao.supersededNoteIds() }.getOrDefault(emptyList()).toSet()
        if (opinions.isEmpty() && retired.isEmpty()) return memories
        return (memories + opinions)
            .filter { it.id !in retired }
            .distinctBy { it.id }
    }

    /** Прежний путь отбора: ограниченный полный скан памяти — в точности как до FTS. */
    private suspend fun fullScan(): List<NoteEntity> = dao.listNotes(kindMemory, memoryScanLimit)
}
