package com.andharness.ru.domain.embedding

/**
 * Primary→fallback source selection for one EmbeddingGemma 2 artifact.
 *
 * This type is deliberately pure and Android-free — no network, no I/O, no files. The actual
 * transfer (streaming, HTTP `Range` resume, SHA-256 verification) lives in `EmbedderDownloadWorker`,
 * which passes a per-source `attempt` lambda. Isolating the ordering and the arbitration rules here
 * makes them unit-testable with fakes, without a device or a socket.
 *
 * Rules:
 * - The PRIMARY source ([EmbedderModelSpec.ModelFile.url], our own server) is always tried first.
 * - The FALLBACK ([EmbedderModelSpec.ModelFile.fallbackUrl], Hugging Face) is tried next, when it is
 *   set and actually different from the primary.
 * - The first source that yields [Outcome.Success] wins; the downloader keeps the resumable `.part`
 *   across sources, so a fallback attempt resumes rather than restarting.
 * - When every source fails, an [Outcome.Retryable] verdict from ANY source is preferred over
 *   [Outcome.Permanent], so a transient outage of the primary still retries (against the fallback)
 *   instead of becoming a dead end.
 */
object EmbedderSourceSelector {

    /**
     * The ordered, de-duplicated download sources for [file]: the primary URL first, then the
     * Hugging Face fallback — dropped when it is blank or identical to the primary.
     */
    fun sources(file: EmbedderModelSpec.ModelFile): List<String> {
        val ordered = LinkedHashSet<String>()
        file.url.takeIf { it.isNotBlank() }?.let(ordered::add)
        file.fallbackUrl
            ?.takeIf { it.isNotBlank() && it != file.url }
            ?.let(ordered::add)
        return ordered.toList()
    }

    /** The verdict of trying ONE source for one file. */
    sealed interface Outcome {

        /** The file was fully downloaded and verified from this source. */
        data object Success : Outcome

        /** A transient failure worth retrying later (network error, timeout, 408/429/5xx). */
        data class Retryable(val reason: String) : Outcome

        /** A definitive rejection (404, size mismatch, checksum mismatch, no space). */
        data class Permanent(val reason: String) : Outcome
    }

    /**
     * Tries [sources] in order via [attempt] and returns the first [Outcome.Success]. When every
     * source fails, a [Outcome.Retryable] verdict takes precedence over [Outcome.Permanent] (the
     * first retryable reason is reported). An empty [sources] list is itself a permanent failure.
     */
    suspend fun tryInOrder(
        sources: List<String>,
        attempt: suspend (String) -> Outcome,
    ): Outcome {
        if (sources.isEmpty()) return Outcome.Permanent(NO_SOURCES)
        var retryable: Outcome.Retryable? = null
        var permanent: Outcome.Permanent? = null
        for (url in sources) {
            when (val outcome = attempt(url)) {
                Outcome.Success -> return Outcome.Success
                is Outcome.Retryable -> if (retryable == null) retryable = outcome
                is Outcome.Permanent -> permanent = outcome
            }
        }
        return retryable ?: permanent ?: Outcome.Permanent(NO_SOURCES)
    }

    /** Honest reason for an empty source list. */
    const val NO_SOURCES = "нет источников загрузки"
}
