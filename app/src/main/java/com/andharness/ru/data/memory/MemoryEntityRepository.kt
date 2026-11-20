package com.andharness.ru.data.memory

import android.util.Log
import com.andharness.ru.data.db.dao.MemoryEntityDao
import com.andharness.ru.data.db.entity.GenericEntity
import com.andharness.ru.data.db.entity.MemoryEntityLinkEntity
import com.andharness.ru.domain.retrieval.ExtractedEntity
import com.andharness.ru.domain.retrieval.MemoryEntityExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a long-term memory's free text and tags into `memory ↔ entity` graph edges and persists them
 * into the `entities` / `memory_entities` tables, so recall can surface memories that share a
 * topic/project/place with a lexically found seed.
 *
 * The detection itself is the pure, deterministic [MemoryEntityExtractor]; this repository only
 * upserts the nodes by [GenericEntity.normalizedName] and writes the resulting
 * [MemoryEntityLinkEntity] rows. Работа ограничена и best-effort: при ошибке записи ничего не
 * удаляется, исключение наружу не пробрасывается — оно только логируется через [Log.w], поэтому
 * линковка памяти никогда не ломает основной сценарий.
 *
 * References:
 * - Pure extractor: [MemoryEntityExtractor].
 * - Node / join entities: [GenericEntity], [MemoryEntityLinkEntity].
 * - Hilt `@Inject`/`@Singleton`:
 *   https://developer.android.com/training/dependency-injection/hilt-android
 * - Room query return types (projections / `suspend` queries):
 *   https://developer.android.com/training/data-storage/room/accessing-data#query-return-types
 */
@Singleton
class MemoryEntityRepository @Inject constructor(
    private val memoryEntityDao: MemoryEntityDao,
) {

    /**
     * Links one memory to the generic entities its [text]/[tags] mention; returns how many links
     * were written.
     *
     * Returns `0` when [noteId] is non-positive, both inputs are blank, nothing is extracted, or the
     * write fails (the failure is logged, not thrown). Existing links are never deleted: an empty
     * extraction simply leaves the tables untouched.
     */
    suspend fun linkMemory(noteId: Long, text: String, tags: String): Int {
        if (noteId <= 0L) return 0
        if (text.isBlank() && tags.isBlank()) return 0
        val extracted = MemoryEntityExtractor.extract(text, tags)
        if (extracted.isEmpty()) return 0
        return runCatching { write(noteId, extracted) }
            .onFailure { Log.w(TAG, "Не удалось связать память с сущностями", it) }
            .getOrDefault(0)
    }

    /**
     * Backfills up to [limit] memories that have no links yet; returns how many were linked.
     *
     * Best-effort: a failing read or write is logged and treated as "nothing linked", so the caller
     * never sees an exception.
     */
    suspend fun linkMissing(limit: Int): Int {
        if (limit <= 0) return 0
        return runCatching {
            var linked = 0
            for (memory in memoryEntityDao.memoriesMissingLinks(limit)) {
                if (linkMemory(memory.noteId, memory.text, memory.tags) > 0) linked++
            }
            linked
        }
            .onFailure { Log.w(TAG, "Не удалось долинковать памяти с сущностями", it) }
            .getOrDefault(0)
    }

    /** Upserts nodes, then the weighted links; returns how many links were written. Never throws. */
    private suspend fun write(noteId: Long, extracted: List<ExtractedEntity>): Int {
        val now = System.currentTimeMillis()
        val resolved = resolveIds(extracted, now)
        if (resolved.isEmpty()) return 0
        val rows = resolved.map { (entity, id) ->
            MemoryEntityLinkEntity(noteId, id, entity.weight, now)
        }
        memoryEntityDao.upsertLinks(rows)
        memoryEntityDao.touchLastSeen(resolved.map { it.second }.distinct(), now)
        return rows.size
    }

    /**
     * Maps extracted entities onto stored node ids. New nodes are inserted (IGNORE on the unique
     * `normalizedName`); the ids Room skipped (`-1`) are then looked up by normalized name, so a
     * concurrent/previous insert reuses the existing row instead of duplicating it.
     */
    private suspend fun resolveIds(
        extracted: List<ExtractedEntity>,
        now: Long
    ): List<Pair<ExtractedEntity, Long>> {
        val nodes = extracted.map { entity ->
            GenericEntity(
                name = entity.name,
                normalizedName = entity.normalizedName,
                kind = entity.kind,
                createdAt = now,
                lastSeenAt = now
            )
        }
        val insertedIds = memoryEntityDao.insertAll(nodes)
        val resolved = ArrayList<Pair<ExtractedEntity, Long>>()
        val unresolvedNames = ArrayList<String>()
        extracted.forEachIndexed { index, entity ->
            val id = insertedIds.getOrElse(index) { -1L }
            if (id > 0L) resolved += entity to id else unresolvedNames += entity.normalizedName
        }
        if (unresolvedNames.isNotEmpty()) {
            val existing = memoryEntityDao.byNormalizedNames(unresolvedNames)
                .associateBy { it.normalizedName }
            extracted.forEachIndexed { index, entity ->
                if (insertedIds.getOrElse(index) { -1L } <= 0L) {
                    val found = existing[entity.normalizedName]
                    if (found != null && found.id > 0L) resolved += entity to found.id
                }
            }
        }
        return resolved
    }

    private companion object {
        const val TAG = "Andharness"
    }
}
