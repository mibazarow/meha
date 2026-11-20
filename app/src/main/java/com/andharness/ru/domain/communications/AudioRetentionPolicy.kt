package com.andharness.ru.domain.communications

/**
 * One stored audio file the retention pass may consider. [id] is the `recordings` row id (never
 * deleted by retention — only the audio file is); [createdAt] is the row's creation time, used as
 * the file's age reference; [keepFlag] pins the audio regardless of age or cache pressure.
 */
data class AudioRetentionCandidate(
    val id: Long,
    val sizeBytes: Long,
    val createdAt: Long,
    val keepFlag: Boolean,
)

/**
 * Which audio files a retention run should delete. [expiredIds] are older than the configured
 * window; [overflowIds] are the oldest of the remaining files that must go to bring the total size
 * under the cache cap. Order is deterministic (expired first, then oldest-first), and the ids never
 * contain a kept recording.
 */
data class AudioRetentionPlan(
    val expiredIds: List<Long>,
    val overflowIds: List<Long>,
) {
    val deleteIds: List<Long> get() = expiredIds + overflowIds
}

/**
 * Pure retention rules for stored audio (transcripts and metadata are never touched — only the
 * audio files are). Kept Android-free so the selection is unit-testable on the JVM.
 *
 * Rules:
 * 1. a candidate with `keepFlag = true` is never selected;
 * 2. a non-kept candidate whose age (`nowMillis - createdAt`) exceeds `keepAudioDays` days is
 *    expired and selected;
 * 3. if the surviving non-kept audio still exceeds `cacheLimitBytes`, the oldest survivors are
 *    selected (oldest `createdAt`, then lowest `id` for a stable order) until the total is at or
 *    under the cap;
 * 4. a non-positive cap deletes every surviving non-kept file; a non-positive size counts as `0`.
 */
object AudioRetentionPolicy {

    const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000

    fun plan(
        candidates: List<AudioRetentionCandidate>,
        nowMillis: Long,
        keepAudioDays: Int,
        cacheLimitBytes: Long,
    ): AudioRetentionPlan {
        val retentionMs = keepAudioDays.coerceAtLeast(0).toLong() * MILLIS_PER_DAY
        val deletable = candidates.filter { !it.keepFlag }

        val expired = deletable.filter { nowMillis - it.createdAt > retentionMs }
        val expiredIds = expired.map { it.id }
        val expiredIdSet = expiredIds.toHashSet()

        val survivors = deletable
            .filter { it.id !in expiredIdSet }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))

        var total = survivors.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        val cap = cacheLimitBytes
        val overflow = ArrayList<Long>()
        for (candidate in survivors) {
            if (total <= cap) break
            overflow += candidate.id
            total -= candidate.sizeBytes.coerceAtLeast(0L)
        }

        return AudioRetentionPlan(expiredIds = expiredIds, overflowIds = overflow)
    }
}
