package com.andharness.ru.data.assist

import android.util.Log
import com.andharness.ru.data.memory.MemoryEntityRepository
import com.andharness.ru.data.memory.MemoryPersonRepository
import com.andharness.ru.data.memory.MemoryVectorRepository

/**
 * Best-effort side-effects of saving a memory: linking it to persons and entities and computing its
 * embedding immediately. Every step is wrapped in `runCatching` and only logs on failure, so it never
 * throws and never affects the save result; with a `null` repository (JVM tests) it is a no-op.
 */
internal class MemoryLinker(
    private val memoryPersonRepository: MemoryPersonRepository?,
    private val memoryEntityRepository: MemoryEntityRepository?,
    private val memoryVectorRepository: MemoryVectorRepository?
) {

    /**
     * Best-effort линковка сохранённой памяти с персонами. Никогда не бросает и не влияет на
     * результат сохранения: при `null`-репозитории (JVM-тесты) это no-op, а при сбое он только
     * логируется. [MemoryPersonRepository.linkMemory] сам ограничен и best-effort.
     */
    suspend fun linkPersons(id: Long, text: String) {
        runCatching { memoryPersonRepository?.linkMemory(id, text) }
            .onFailure { Log.w(TAG, "Не удалось связать память с персонами", it) }
    }

    /**
     * Best-effort линковка сохранённой памяти с обобщёнными сущностями (темы/проекты/места).
     * Никогда не бросает и не влияет на результат сохранения: при `null`-репозитории (JVM-тесты)
     * это no-op, а при сбое он только логируется. [MemoryEntityRepository.linkMemory] сам ограничен
     * и best-effort.
     */
    suspend fun linkEntities(id: Long, text: String, tags: String) {
        runCatching { memoryEntityRepository?.linkMemory(id, text, tags) }
            .onFailure { Log.w(TAG, "Не удалось связать память с сущностями", it) }
    }

    /** Best-effort немедленный эмбеддинг только что сохранённой памяти; никогда не бросает. */
    suspend fun embedMemory(id: Long, text: String, tags: String) {
        runCatching { memoryVectorRepository?.embedOne(id, text, tags) }
            .onFailure { Log.w(TAG, "Не удалось посчитать эмбеддинг памяти", it) }
    }

    private companion object {
        const val TAG = "Andharness"
    }
}
