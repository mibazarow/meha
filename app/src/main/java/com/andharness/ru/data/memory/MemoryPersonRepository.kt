package com.andharness.ru.data.memory

import android.util.Log
import com.andharness.ru.data.db.dao.MemoryPersonDao
import com.andharness.ru.data.db.dao.PersonAliasDao
import com.andharness.ru.data.db.dao.PersonDao
import com.andharness.ru.data.db.entity.MemoryPersonEntity
import com.andharness.ru.domain.retrieval.MemoryPersonLinker
import com.andharness.ru.domain.retrieval.PersonNameCandidate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a long-term memory's free text into `memory ↔ person` graph edges and persists them into
 * the `memory_persons` join table, so recall can answer "what do I know about this person" by
 * traversing the graph.
 *
 * The detection itself is the pure, deterministic [MemoryPersonLinker]; this repository only loads
 * the bounded candidate set (persons + their aliases) and writes the resulting
 * [MemoryPersonEntity] rows. Работа ограничена и best-effort: набор кандидатов ограничен
 * ([MAX_PERSONS] персон и [MAX_ALIASES] алиасов), при ошибке записи ничего не удаляется,
 * исключение наружу не пробрасывается — оно только логируется через [Log.w], поэтому линковка
 * памяти никогда не ломает основной сценарий. Если персон в базе нет, кандидатов нет и
 * репозиторий просто ничего не пишет.
 *
 * References:
 * - Pure linker: [MemoryPersonLinker].
 * - Join entity / table: [MemoryPersonEntity].
 * - Hilt `@Inject`/`@Singleton`:
 *   https://developer.android.com/training/dependency-injection/hilt-android
 * - Room query return types (projections / `suspend` queries):
 *   https://developer.android.com/training/data-storage/room/accessing-data#query-return-types
 */
@Singleton
class MemoryPersonRepository @Inject constructor(
    private val personDao: PersonDao,
    private val personAliasDao: PersonAliasDao,
    private val memoryPersonDao: MemoryPersonDao,
) {

    /**
     * Links one memory to the persons its [text] mentions; returns how many links were written.
     *
     * Returns `0` when [noteId] is non-positive, [text] is blank, no person matches, or the write
     * fails (the failure is logged, not thrown). Existing links are never deleted: an empty match
     * simply leaves the table untouched.
     */
    suspend fun linkMemory(noteId: Long, text: String): Int {
        if (noteId <= 0L || text.isBlank()) return 0
        val candidates = candidates()
        if (candidates.isEmpty()) return 0
        return linkWith(candidates, noteId, text)
    }

    /**
     * Backfills up to [limit] memories that have no links yet; returns how many were linked.
     *
     * The candidate set (persons + aliases) is loaded **once** for the whole batch and reused for
     * every memory, so a batch of [limit] memories does not trigger [limit] person/alias scans.
     *
     * Best-effort: a failing read or write is logged and treated as "nothing linked", so the caller
     * never sees an exception.
     */
    suspend fun linkMissing(limit: Int): Int {
        if (limit <= 0) return 0
        return runCatching {
            val candidates = candidates()
            if (candidates.isEmpty()) return@runCatching 0
            var linked = 0
            for (memory in memoryPersonDao.memoriesMissingLinks(limit)) {
                if (linkWith(candidates, memory.noteId, memory.text) > 0) linked++
            }
            linked
        }
            .onFailure { Log.w(TAG, "Не удалось долинковать памяти с персонами", it) }
            .getOrDefault(0)
    }

    /**
     * Pure-ish linking core shared by [linkMemory] and [linkMissing]: runs the deterministic
     * [MemoryPersonLinker] over an already-loaded [candidates] set and upserts the resulting rows.
     * Returns how many links were written; never throws (a write failure is logged via [Log.w]).
     */
    private suspend fun linkWith(
        candidates: List<PersonNameCandidate>,
        noteId: Long,
        text: String,
    ): Int {
        if (noteId <= 0L || text.isBlank()) return 0
        val links = MemoryPersonLinker.link(text, candidates)
        if (links.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val rows = links.map { MemoryPersonEntity(noteId, it.personId, it.weight, now) }
        var written = 0
        runCatching { memoryPersonDao.upsertAll(rows) }
            .onFailure { Log.w(TAG, "Не удалось записать связи памяти с персонами", it) }
            .onSuccess { written = rows.size }
        return written
    }

    /** Bounded candidate set: every person (capped) with their display name plus aliases. */
    private suspend fun candidates(): List<PersonNameCandidate> {
        val persons = personDao.all(MAX_PERSONS)
        if (persons.isEmpty()) return emptyList()
        val aliasesByPerson = personAliasDao.all(MAX_ALIASES).groupBy { it.personId }
        return persons.map { person ->
            val names = ArrayList<String>()
            names += person.displayName
            aliasesByPerson[person.id]?.forEach { names += it.alias }
            PersonNameCandidate(person.id, names)
        }
    }

    private companion object {
        const val TAG = "Andharness"

        /** Bounded scan caps, same order of magnitude as recall's candidate scans. */
        const val MAX_PERSONS = 1000
        const val MAX_ALIASES = 2000
    }
}
