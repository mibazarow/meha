package com.andharness.ru.data.assist

import com.andharness.ru.data.db.dao.AssistDao
import com.andharness.ru.data.db.dao.MemoryEntityDao
import com.andharness.ru.data.db.dao.MemoryPersonDao
import com.andharness.ru.data.db.dao.MemoryVectorDao
import com.andharness.ru.data.db.dao.NoteFtsDao
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.db.entity.TodoEntity
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.data.memory.MemoryEntityRepository
import com.andharness.ru.data.memory.MemoryPersonRepository
import com.andharness.ru.data.memory.MemoryVectorRepository
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.assist.AssistStats
import com.andharness.ru.domain.agent.RecallIntent
import com.andharness.ru.domain.assist.MemoryHit
import com.andharness.ru.domain.assist.MemorySaveResult
import com.andharness.ru.domain.memory.MemoryJunkFilter
import com.andharness.ru.domain.retrieval.MemoryConsolidation
import com.andharness.ru.domain.retrieval.MemoryRanker
import com.andharness.ru.domain.retrieval.MemoryRecord
import com.andharness.ru.domain.retrieval.MemorySavePlan
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssistRepositoryImpl @Inject constructor(
    private val dao: AssistDao,
    /**
     * FTS-шортлист. Необязателен только ради JVM-тестов, которые проверяют прежнее
     * полносканирующее поведение: при `null` repo всегда идёт старым путём. В приложении Hilt
     * подставляет реальный [NoteFtsDao].
     */
    private val noteFtsDao: NoteFtsDao? = null,
    /**
     * Хранилище плотных эмбеддингов памяти. Необязательно ради JVM-тестов: при
     * `null` семантическая ветка полностью пропускается и recall остаётся прежним лексическим.
     */
    private val memoryVectorDao: MemoryVectorDao? = null,
    /**
     * Встроенный офлайн-эмбеддер (ONNX, `rubert-tiny-turbo-int8`); `null` (тесты) тоже означает
     * «семантики нет» и полностью сохраняет прежний лексический recall.
     */
    private val localEmbedder: LocalEmbedder? = null,
    /** Настройки с переключателем `semanticSearchEnabled`; `null` — семантика выключена. */
    private val settingsStore: LlmSettingsStore? = null,
    /**
     * Линковка памяти с персонами (`memory ↔ person`). Необязательна ради JVM-тестов: при
     * `null` граф не пишется и сохранение памяти работает ровно как прежде. Линковка всегда
     * best-effort и никогда не влияет на результат сохранения. В приложении Hilt подставляет
     * реальный [MemoryPersonRepository].
     */
    private val memoryPersonRepository: MemoryPersonRepository? = null,
    /**
     * Чтение графа `memory ↔ person` для recall. Необязательно ради JVM-тестов: при `null`
     * графовое расширение полностью пропускается и recall работает ровно как прежде (только
     * лексика/семантика). В приложении Hilt подставляет реальный [MemoryPersonDao]. Расширение
     * всегда best-effort и никогда не бросает.
     */
    private val memoryPersonDao: MemoryPersonDao? = null,
    /**
     * Немедленный эмбеддинг только что сохранённой памяти (best-effort, чтобы парафраз находился
     * семантическим recall сразу, а не после периодического воркера). Необязателен ради JVM-тестов:
     * при `null` эмбеддинг при сохранении пропускается и поведение recall не меняется. В приложении
     * Hilt подставляет реальный [MemoryVectorRepository].
     */
    private val memoryVectorRepository: MemoryVectorRepository? = null,
    /**
     * Линковка памяти с обобщёнными сущностями (`memory ↔ entity`: темы/проекты/места).
     * Необязательна ради JVM-тестов: при `null` граф сущностей не пишется и сохранение памяти
     * работает ровно как прежде. Линковка всегда best-effort и никогда не влияет на результат
     * сохранения. В приложении Hilt подставляет реальный [MemoryEntityRepository].
     */
    private val memoryEntityRepository: MemoryEntityRepository? = null,
    /**
     * Чтение графа `memory ↔ entity` для recall. Необязательно ради JVM-тестов: при `null`
     * расширение по сущностям пропускается. В приложении Hilt подставляет реальный
     * [MemoryEntityDao]. Расширение всегда best-effort и никогда не бросает.
     */
    private val memoryEntityDao: MemoryEntityDao? = null
) : AssistRepository {

    /** Candidate selection: FTS shortlist or bounded full scan, plus actual opinions. */
    private val candidateLoader = MemoryCandidateLoader(dao, noteFtsDao, KIND_MEMORY, MEMORY_SCAN_LIMIT)

    /** Graph expansion along `memory ↔ person` and `memory ↔ entity`. */
    private val graphExpander = MemoryGraphExpander(dao, memoryPersonDao, memoryEntityDao, KIND_MEMORY)

    /** Best-effort semantic vectors for BM25+cosine fusion (no-op without a model/store). */
    private val semanticVectors = MemorySemanticVectors(memoryVectorDao, localEmbedder, settingsStore)

    /** Best-effort semantic duplicate check at write time (BUGS.md M6). */
    private val semanticDedup = MemorySemanticDedup(memoryVectorDao, localEmbedder, settingsStore)

    /** Best-effort save-time side-effects: person/entity links + immediate embedding. */
    private val linker = MemoryLinker(
        memoryPersonRepository,
        memoryEntityRepository,
        memoryVectorRepository
    )

    override suspend fun listNotes(kind: String, limit: Int): List<NoteEntity> = dao.listNotes(kind, limit)

    override suspend fun searchNotes(kind: String, query: String, limit: Int): List<NoteEntity> =
        dao.searchNotes(kind, query, limit)

    override suspend fun addNote(kind: String, text: String): Long =
        dao.insertNote(NoteEntity(kind = kind, text = text))

    override suspend fun deleteNoteById(id: Long): Boolean = dao.deleteNoteById(id, KIND_NOTE) > 0

    override suspend fun listMemories(limit: Int): List<NoteEntity> = dao.listNotes(KIND_MEMORY, limit)

    override suspend fun listMemoriesForGc(limit: Int): List<NoteEntity> =
        dao.listNotesOldestByUse(KIND_MEMORY, limit)

    override suspend fun deleteMemory(id: Long): Boolean = dao.deleteNoteById(id, KIND_MEMORY) > 0

    override suspend fun saveOpinion(
        text: String,
        tags: String,
        confidence: Float,
        supersedesId: Long
    ): Long {
        val trimmedText = text.trim()
        val trimmedTags = tags.trim()
        val id = dao.insertNote(
            NoteEntity(
                kind = KIND_OPINION,
                text = trimmedText,
                tags = trimmedTags,
                confidence = confidence.coerceIn(0f, 1f),
                supersedesId = supersedesId.coerceAtLeast(0L)
            )
        )
        linker.linkPersons(id, trimmedText)
        linker.linkEntities(id, trimmedText, trimmedTags)
        linker.embedMemory(id, trimmedText, trimmedTags)
        return id
    }

    override suspend fun listOpinions(limit: Int): List<NoteEntity> = dao.listOpinions(limit)

    override suspend fun supersededNoteIds(): Set<Long> = dao.supersededNoteIds().toSet()

    override suspend fun stats(): AssistStats = AssistStats(
        noteCount = dao.countByKind(KIND_NOTE),
        memoryCount = dao.countByKind(KIND_MEMORY),
        memoryByImportance = dao.countByImportance(KIND_MEMORY)
            .associate { it.importance to it.count }
    )

    override suspend fun saveMemory(text: String, tags: String, importance: Int): Long {
        val trimmedText = text.trim()
        // Same junk gate as the consolidated path (BUGS.md M1). Callers already treat 0 as
        // "not saved" and say so honestly, so a filler never becomes a memory.
        if (!MemoryJunkFilter.shouldStore(trimmedText)) return 0L
        val id = dao.insertNote(
            NoteEntity(
                kind = KIND_MEMORY,
                text = trimmedText,
                tags = tags.trim(),
                importance = importance.coerceIn(0, 2)
            )
        )
        linker.linkPersons(id, trimmedText)
        linker.linkEntities(id, trimmedText, tags.trim())
        linker.embedMemory(id, trimmedText, tags.trim())
        return id
    }

    override suspend fun saveMemoryConsolidated(
        text: String,
        tags: String,
        importance: Int
    ): MemorySaveResult {
        val trimmedText = text.trim()
        val trimmedTags = tags.trim()
        val value = importance.coerceIn(0, 2)
        // Junk gate before anything is written (BUGS.md M1): a filler or a narration about the
        // conversation must never reach `notes`, where it would be injected into every later prompt.
        if (!MemoryJunkFilter.shouldStore(trimmedText)) {
            return MemorySaveResult(
                id = 0L,
                created = false,
                text = trimmedText,
                tags = trimmedTags,
                importance = value,
                rejected = true
            )
        }
        val now = System.currentTimeMillis()
        val existingNotes = dao.listNotes(KIND_MEMORY, MEMORY_SCAN_LIMIT)
        val existing = existingNotes.map { it.toRecord() }
        // Text consolidation first (cheap, no model). If it finds nothing, ask the semantic layer
        // whether the SAME fact was already said in other words (BUGS.md M6) and, when it was,
        // update that row instead of inserting a twin.
        val textPlan = MemoryConsolidation.decide(existing, trimmedText, trimmedTags, value, now)
        val plan = when (textPlan) {
            is MemorySavePlan.Insert -> {
                val semanticDuplicate = runCatching {
                    semanticDedup.duplicateOf(trimmedText, existingNotes)
                }.getOrNull()
                val target = semanticDuplicate?.let { id ->
                    existing.firstOrNull { it.id == id }
                }
                if (target == null) {
                    textPlan
                } else {
                    MemorySavePlan.Update(
                        id = target.id,
                        text = trimmedText,
                        tags = MemoryConsolidation.mergeTags(target.tags, trimmedTags),
                        importance = maxOf(target.importance, value).coerceIn(0, 2),
                        lastUsedAt = now
                    )
                }
            }
            is MemorySavePlan.Update -> textPlan
        }
        return when (plan) {
            is MemorySavePlan.Insert -> {
                val id = dao.insertNote(
                    NoteEntity(
                        kind = KIND_MEMORY,
                        text = plan.text,
                        tags = plan.tags,
                        importance = plan.importance
                    )
                )
                linker.linkPersons(id, plan.text)
                linker.linkEntities(id, plan.text, plan.tags)
                linker.embedMemory(id, plan.text, plan.tags)
                MemorySaveResult(
                    id = id,
                    created = true,
                    text = plan.text,
                    tags = plan.tags,
                    importance = plan.importance
                )
            }
            is MemorySavePlan.Update -> {
                dao.updateMemory(plan.id, plan.text, plan.tags, plan.importance, plan.lastUsedAt)
                linker.linkPersons(plan.id, plan.text)
                linker.linkEntities(plan.id, plan.text, plan.tags)
                linker.embedMemory(plan.id, plan.text, plan.tags)
                MemorySaveResult(
                    id = plan.id,
                    created = false,
                    text = plan.text,
                    tags = plan.tags,
                    importance = plan.importance
                )
            }
        }
    }

    override suspend fun searchMemories(query: String, limit: Int): List<NoteEntity> =
        searchMemoriesRanked(query, limit)
            .filter { it.lexicallyMatched }
            .map { it.note }

    override suspend fun searchMemoryCandidates(query: String, limit: Int): List<NoteEntity> =
        candidateLoader.candidates(query, limit, semanticEnabled())

    override suspend fun searchMemoriesRanked(query: String, limit: Int): List<MemoryHit> {
        // With semantics on, do not pre-filter candidates by literal overlap: the whole point of
        // embedding recall is that a paraphrase may have NO lexical match yet still be the best hit.
        val all = candidateLoader.candidates(query, MEMORY_SCAN_LIMIT, semanticEnabled())
        if (all.isEmpty()) return emptyList()
        val (candidates, graphIds) = graphExpander.expand(all)
        val now = System.currentTimeMillis()
        val byId = candidates.associateBy { it.id }
        // Semantic reranker: both vectors must be available, otherwise this is a
        // no-op and MemoryRanker falls back to EXACTLY the previous BM25 ranking.
        val (queryVector, docVectors) = semanticVectors.vectors(query, candidates)
        // A question about the past must reach back: the age prior is neutralised for this turn
        // (BUGS.md M2). The decision comes from the existing, tested RecallIntent — never guessed.
        val pastRecall = RecallIntent.hintFor(query) != null
        val hits = MemoryRanker.rank(
            query = query,
            memories = candidates.map { it.toRecord() },
            limit = limit,
            now = now,
            queryVector = queryVector,
            docVectors = docVectors,
            graphIds = graphIds,
            ignoreRecency = pastRecall
        ).mapNotNull { match ->
            byId[match.id]?.let { MemoryHit(it, match.score, match.lexicallyMatched) }
        }
        // Only genuinely recalled (lexically matched) rows count as "used"; the weak importance
        // fallback must not keep refreshing itself through its own recency prior.
        hits.filter { it.lexicallyMatched }.forEach { dao.touchNote(it.note.id, now) }
        return hits
    }

    /** `true` when the user enabled built-in semantic recall (checked once per search). */
    private suspend fun semanticEnabled(): Boolean =
        settingsStore?.config?.firstOrNull()?.semanticSearchEnabled == true

    private fun NoteEntity.toRecord() = MemoryRecord(
        id = id,
        text = text,
        tags = tags,
        kind = kind,
        importance = importance,
        lastUsedAt = lastUsedAt,
        createdAt = createdAt,
        accessCount = accessCount,
        confidence = confidence
    )

    private companion object {
        const val KIND_NOTE = "note"
        const val KIND_MEMORY = "memory"
        const val KIND_OPINION = "opinion"
        const val MEMORY_SCAN_LIMIT = 500
    }

    override suspend fun listTodos(limit: Int): List<TodoEntity> = dao.listTodos(limit)

    override suspend fun addTodo(text: String): Long = dao.insertTodo(TodoEntity(text = text))

    override suspend fun setTodoDone(id: Long, done: Boolean): Boolean {
        dao.getTodo(id) ?: return false
        dao.setTodoDone(id, done)
        return true
    }

    override suspend fun deleteTodo(id: Long): Boolean = dao.deleteTodo(id) > 0
}
