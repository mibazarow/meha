package com.andharness.ru.data.assist

import com.andharness.ru.data.db.dao.AssistDao
import com.andharness.ru.data.db.dao.MemoryEntityDao
import com.andharness.ru.data.db.dao.MemoryPersonDao
import com.andharness.ru.data.db.entity.NoteEntity

/**
 * Best-effort expansion of the recall candidate pool along the `memory ↔ person` and
 * `memory ↔ entity` graphs: it takes the persons/entities of the lexically found "seeds", then the
 * memories linked to them, and unions both graphs. Every DB access is wrapped in `runCatching`, so
 * graph expansion never throws and never breaks recall; with both DAOs `null` the original list and
 * an empty id set are returned (previous behaviour).
 */
internal class MemoryGraphExpander(
    private val dao: AssistDao,
    private val memoryPersonDao: MemoryPersonDao?,
    private val memoryEntityDao: MemoryEntityDao?,
    private val kindMemory: String
) {

    /**
     * Returns `(combined list, graphIds)` where the id set holds only the ids actually present in the
     * combined list (their presence makes a memory a match with [com.andharness.ru.domain.retrieval.MemoryRanker.GRAPH_BONUS]).
     */
    suspend fun expand(memories: List<NoteEntity>): Pair<List<NoteEntity>, Set<Long>> {
        val personDao = memoryPersonDao
        val entityDao = memoryEntityDao
        if (personDao == null && entityDao == null) return memories to emptySet()
        val seedIds = memories.take(MAX_SEED_MEMORIES).map { it.id }
        if (seedIds.isEmpty()) return memories to emptySet()

        val personGraphIds: Set<Long> = if (personDao != null) {
            val personIds = runCatching { personDao.personIdsForNotes(seedIds) }
                .getOrDefault(emptyList())
                .take(MAX_SEED_PERSONS)
            personIds.flatMap { personId ->
                runCatching { personDao.noteIdsForPerson(personId, GRAPH_PER_PERSON) }
                    .getOrDefault(emptyList())
            }.toSet()
        } else emptySet()

        val entityGraphIds: Set<Long> = if (entityDao != null) {
            val entityIds = runCatching { entityDao.entityIdsForNotes(seedIds) }
                .getOrDefault(emptyList())
                .take(MAX_SEED_ENTITIES)
            entityIds.flatMap { entityId ->
                runCatching { entityDao.noteIdsForEntity(entityId, GRAPH_PER_ENTITY) }
                    .getOrDefault(emptyList())
            }.toSet()
        } else emptySet()

        val graphIds = personGraphIds + entityGraphIds
        val ownIds = memories.map { it.id }.toSet()
        val newIds = (graphIds - ownIds).take(MAX_GRAPH_EXTRA)
        if (newIds.isEmpty()) return memories to graphIds.intersect(ownIds)
        val extra = runCatching { dao.listNotesByIds(kindMemory, newIds) }
            .getOrDefault(emptyList())
        val combined = memories + extra
        return combined to graphIds.intersect(combined.map { it.id }.toSet())
    }

    private companion object {
        /** Сколько лексически найденных «семян» максимум отдаём в графовое расширение. */
        const val MAX_SEED_MEMORIES = 25

        /** Сколько персон максимум берём из графа у этих семян (ограничивает fan-out). */
        const val MAX_SEED_PERSONS = 5

        /** Сколько связанных памятей максимум берём на одну персону. */
        const val GRAPH_PER_PERSON = 10

        /**
         * Сколько сущностей максимум берём из графа у «семян» (ограничивает fan-out); тот же
         * порядок величины, что [MAX_SEED_PERSONS].
         */
        const val MAX_SEED_ENTITIES = 5

        /** Сколько связанных памятей максимум берём на одну сущность; тот же предел, что у персон. */
        const val GRAPH_PER_ENTITY = 10

        /** Сколько НОВЫХ (ещё не в пуле) памятей максимум добавляем через граф. */
        const val MAX_GRAPH_EXTRA = 25
    }
}
