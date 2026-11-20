package com.andharness.ru.domain.downloads

import com.andharness.ru.domain.formatting.ByteFormat
import com.andharness.ru.domain.localserver.FreeSpaceDecision

/**
 * Pure, Android-free policy shared by every on-device model download (the local LLM weights, the
 * speech models and the EmbeddingGemma 2 embedder): free-space classification and its honest
 * Russian message, plus download-progress math.
 *
 * Extracted so the SAME rules back every model spec instead of being copied per model. Specs
 * (`LocalModelSpec`, `EmbedderModelSpec`, `SpeechModel`) expose thin delegating helpers so callers
 * keep a spec-scoped API while the arithmetic lives in exactly one place.
 *
 * No Android imports: every function is unit-testable on the JVM.
 */
object ModelDownloadPolicy {

    /** Recommended free-space headroom as a fraction: at least 1.25x the download. */
    private const val FREE_SPACE_MARGIN_NUMERATOR = 5L
    private const val FREE_SPACE_MARGIN_DENOMINATOR = 4L

    /**
     * Classifies the free space on the target filesystem. Pure arithmetic:
     * `INSUFFICIENT` when [freeBytes] < [requiredBytes], `LOW` when it is below 1.25x, else `OK`.
     * A non-positive [requiredBytes] is always `OK` (nothing to download).
     */
    fun freeSpaceDecision(freeBytes: Long, requiredBytes: Long): FreeSpaceDecision {
        if (requiredBytes <= 0L) return FreeSpaceDecision.OK
        if (freeBytes < requiredBytes) return FreeSpaceDecision.INSUFFICIENT
        if (freeBytes < requiredWithMargin(requiredBytes)) return FreeSpaceDecision.LOW
        return FreeSpaceDecision.OK
    }

    /** The size that must be free to avoid the [FreeSpaceDecision.LOW] warning (download x 1.25). */
    fun requiredWithMargin(requiredBytes: Long): Long =
        requiredBytes * FREE_SPACE_MARGIN_NUMERATOR / FREE_SPACE_MARGIN_DENOMINATOR

    /**
     * Honest Russian explanation of a [FreeSpaceDecision]. For `LOW`/`INSUFFICIENT` it says how many
     * bytes are still missing, so the user can act instead of seeing a generic error.
     */
    fun freeSpaceMessage(
        decision: FreeSpaceDecision,
        freeBytes: Long,
        requiredBytes: Long,
    ): String = when (decision) {
        FreeSpaceDecision.OK ->
            "Достаточно свободного места для загрузки модели."

        FreeSpaceDecision.LOW ->
            "Свободного места мало: загрузка может не завершиться. " +
                "Рекомендуется освободить ещё ~${ByteFormat.humanBytes(missingBytes(freeBytes, requiredWithMargin(requiredBytes)))}."

        FreeSpaceDecision.INSUFFICIENT ->
            "Недостаточно свободного места для загрузки модели: " +
                "нужно ещё ~${ByteFormat.humanBytes(missingBytes(freeBytes, requiredBytes))}."
    }

    /**
     * Download completion in whole percent, clamped to `0..100`. A non-positive [totalBytes] means
     * the total is unknown, which is reported honestly as `0` rather than a guessed value. A
     * non-positive [downloadedBytes] is `0`.
     */
    fun progressPercent(downloadedBytes: Long, totalBytes: Long): Int {
        if (downloadedBytes <= 0L || totalBytes <= 0L) return 0
        val percent = downloadedBytes * 100L / totalBytes
        return percent.coerceIn(0L, 100L).toInt()
    }

    private fun missingBytes(freeBytes: Long, neededBytes: Long): Long =
        (neededBytes - freeBytes).coerceAtLeast(0L)
}
