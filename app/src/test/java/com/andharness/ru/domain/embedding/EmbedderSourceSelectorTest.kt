package com.andharness.ru.domain.embedding

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [EmbedderSourceSelector]: source ordering (primary first, Hugging Face fallback
 * second) and the primary→fallback arbitration. The per-source transfer is a fake `attempt` lambda,
 * so no network, device or I/O is involved.
 */
class EmbedderSourceSelectorTest {

    private val spec = EmbedderModelSpec.ModelFile(
        fileName = "a.gguf",
        url = "https://primary/a.gguf",
        sizeBytes = 10L,
        sha256 = "0".repeat(64),
        fallbackUrl = "https://fallback/a.gguf",
    )

    // --- ordering ---------------------------------------------------------------------------

    @Test
    fun primaryIsFirstAndFallbackIsSecond() {
        assertEquals(
            listOf("https://primary/a.gguf", "https://fallback/a.gguf"),
            EmbedderSourceSelector.sources(spec),
        )
    }

    @Test
    fun missingFallbackYieldsOnlyPrimary() {
        assertEquals(
            listOf("https://primary/a.gguf"),
            EmbedderSourceSelector.sources(spec.copy(fallbackUrl = null)),
        )
    }

    @Test
    fun blankOrDuplicateFallbackIsDropped() {
        assertEquals(
            listOf("https://primary/a.gguf"),
            EmbedderSourceSelector.sources(spec.copy(fallbackUrl = "   ")),
        )
        assertEquals(
            listOf("https://primary/a.gguf"),
            EmbedderSourceSelector.sources(spec.copy(fallbackUrl = spec.url)),
        )
    }

    // --- arbitration (attempt is a fake) ----------------------------------------------------

    @Test
    fun primarySuccessStopsBeforeTryingTheFallback() = runTest {
        val tried = mutableListOf<String>()
        val outcome = EmbedderSourceSelector.tryInOrder(EmbedderSourceSelector.sources(spec)) { url ->
            tried += url
            EmbedderSourceSelector.Outcome.Success
        }
        assertEquals(EmbedderSourceSelector.Outcome.Success, outcome)
        assertEquals(listOf("https://primary/a.gguf"), tried)
    }

    @Test
    fun primaryPermanentFailureFallsBackToTheNextSource() = runTest {
        val tried = mutableListOf<String>()
        val outcome = EmbedderSourceSelector.tryInOrder(EmbedderSourceSelector.sources(spec)) { url ->
            tried += url
            if (url.startsWith("https://primary")) {
                EmbedderSourceSelector.Outcome.Permanent("сервер ответил 404")
            } else {
                EmbedderSourceSelector.Outcome.Success
            }
        }
        assertEquals(EmbedderSourceSelector.Outcome.Success, outcome)
        assertEquals(listOf("https://primary/a.gguf", "https://fallback/a.gguf"), tried)
    }

    @Test
    fun primaryRetryableFailureFallsBackAndSucceeds() = runTest {
        val tried = mutableListOf<String>()
        val outcome = EmbedderSourceSelector.tryInOrder(EmbedderSourceSelector.sources(spec)) { url ->
            tried += url
            if (url.startsWith("https://primary")) {
                EmbedderSourceSelector.Outcome.Retryable("тайм-аут")
            } else {
                EmbedderSourceSelector.Outcome.Success
            }
        }
        assertEquals(EmbedderSourceSelector.Outcome.Success, outcome)
        assertEquals(listOf("https://primary/a.gguf", "https://fallback/a.gguf"), tried)
    }

    @Test
    fun everySourceFailingPrefersRetryableOverPermanent() = runTest {
        val outcome = EmbedderSourceSelector.tryInOrder(EmbedderSourceSelector.sources(spec)) { url ->
            if (url.startsWith("https://primary")) {
                EmbedderSourceSelector.Outcome.Permanent("сервер ответил 404")
            } else {
                EmbedderSourceSelector.Outcome.Retryable("сетевая ошибка")
            }
        }
        assertEquals(EmbedderSourceSelector.Outcome.Retryable("сетевая ошибка"), outcome)
    }

    @Test
    fun allPermanentFailuresReturnTheLastPermanent() = runTest {
        val outcome = EmbedderSourceSelector.tryInOrder(EmbedderSourceSelector.sources(spec)) { url ->
            EmbedderSourceSelector.Outcome.Permanent("bad $url")
        }
        assertEquals(
            EmbedderSourceSelector.Outcome.Permanent("bad https://fallback/a.gguf"),
            outcome,
        )
    }

    @Test
    fun emptySourceListIsAPermanentFailure() = runTest {
        val outcome = EmbedderSourceSelector.tryInOrder(emptyList()) {
            EmbedderSourceSelector.Outcome.Success
        }
        assertEquals(
            EmbedderSourceSelector.Outcome.Permanent(EmbedderSourceSelector.NO_SOURCES),
            outcome,
        )
    }

    // --- wiring to the real spec ------------------------------------------------------------

    @Test
    fun realSpecDownloadsFromHuggingFaceOnly() {
        assertEquals(2, EmbedderModelSpec.FILES.size)
        EmbedderModelSpec.FILES.forEach { file ->
            val sources = EmbedderSourceSelector.sources(file)
            assertEquals("no project-owned mirror any more", 1, sources.size)
            assertEquals(file.url, sources[0])
            assertTrue(sources[0].startsWith(EmbedderModelSpec.HF_BASE_URL))
        }
    }
}
