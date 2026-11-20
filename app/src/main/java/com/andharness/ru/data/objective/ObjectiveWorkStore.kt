package com.andharness.ru.data.objective

import com.andharness.ru.domain.objective.ObjectiveConcurrencyPolicy
import com.andharness.ru.domain.serialization.AppJson
import java.io.File

/**
 * Persisted, process-surviving configuration of the background objective runner: the concurrency
 * limit, the last tick time and the last honest error. Written atomically (temp file + rename) to
 * `<rootDir>/workers.json`, the same pattern as [ObjectiveStore].
 *
 * It is deliberately Android-free (it only takes a directory [File]), so the clamping of the limit
 * and the round-trip are unit-tested on the JVM. The RUNNING/QUEUED sets are NOT persisted: a run
 * cannot survive a process restart, so persisting them would let the app claim work is running when
 * it is not — after a restart the periodic job simply re-enqueues eligible objectives.
 */
class ObjectiveWorkStore(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    /** Current persisted config, degrading to defaults on a missing/corrupt file. Never throws. */
    @Synchronized
    fun load(): ObjectiveWorkConfig = try {
        if (file.isFile) CODEC.decodeFromString(SERIALIZER, file.readText()) else ObjectiveWorkConfig()
    } catch (_: Exception) {
        ObjectiveWorkConfig()
    }

    /** The persisted concurrency limit, clamped to 1..3. */
    @Synchronized
    fun maxConcurrency(): Int = ObjectiveConcurrencyPolicy.clamp(load().maxConcurrency)

    /**
     * Persists a new concurrency [value], clamped to 1..3; returns the value actually stored.
     */
    @Synchronized
    fun setMaxConcurrency(value: Int): Int {
        val clamped = ObjectiveConcurrencyPolicy.clamp(value)
        persist(load().copy(maxConcurrency = clamped))
        return clamped
    }

    /** Records the last tick time and an honest error note (null clears it). */
    @Synchronized
    fun recordTick(now: Long, error: String?) {
        persist(load().copy(lastTickAt = now, lastError = error))
    }

    private fun persist(next: ObjectiveWorkConfig) {
        try {
            rootDir.mkdirs()
            val temp = File(rootDir, "$FILE_NAME.tmp")
            val encoded = CODEC.encodeToString(SERIALIZER, next)
            temp.writeText(encoded)
            if (!temp.renameTo(file)) {
                file.writeText(encoded)
                temp.delete()
            }
        } catch (_: Exception) {
            // Best-effort: a failed write leaves the previous config on disk unchanged.
        }
    }

    companion object {
        const val DIR_NAME = ObjectiveStore.DIR_NAME
        const val FILE_NAME = "workers.json"

        private val CODEC = AppJson.LenientEncodeDefaults
        private val SERIALIZER = ObjectiveWorkConfig.serializer()
    }
}
