package com.andharness.ru.domain.embedding

import com.andharness.ru.domain.downloads.ModelDownloadPolicy
import com.andharness.ru.domain.localserver.FreeSpaceDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the EmbeddingGemma 2 artifact spec: the two served files, their exact size/SHA-256,
 * and the shared free-space/progress policy delegation. No Android or network involved.
 */
class EmbedderModelSpecTest {

    @Test
    fun specHasTwoFilesOnHuggingFace() {
        assertEquals(2, EmbedderModelSpec.FILES.size)
        EmbedderModelSpec.FILES.forEach { file ->
            assertTrue(file.url.startsWith(EmbedderModelSpec.HF_BASE_URL))
            assertTrue(file.url.contains(file.fileName))
            assertTrue(file.fileName.endsWith(".gguf"))
        }
        assertEquals(EmbedderModelSpec.MODEL, EmbedderModelSpec.FILES[0])
        assertEquals(EmbedderModelSpec.MMPROJ, EmbedderModelSpec.FILES[1])
    }

    /**
     * There is no project-owned download server any more, so Hugging Face is the single source.
     * A fallback is allowed but must never be the same URL and must point at the same upstream
     * artifact — that is what the downloader's verification assumes.
     */
    @Test
    fun aFallbackIsOptionalButNeverThePrimaryItself() {
        EmbedderModelSpec.FILES.forEach { file ->
            val fallback = file.fallbackUrl ?: return@forEach
            assertTrue("fallback must differ from the primary", fallback != file.url)
            assertTrue("fallback must serve the same file", fallback.contains(file.fileName))
        }
        assertNull("no mirror is configured today", EmbedderModelSpec.MODEL.fallbackUrl)
        assertNull("no mirror is configured today", EmbedderModelSpec.MMPROJ.fallbackUrl)
    }

    @Test
    fun weightsAreTheExactServedArtifact() {
        assertEquals("embeddinggemma-2-Q8_0.gguf", EmbedderModelSpec.MODEL.fileName)
        assertEquals(
            "${EmbedderModelSpec.HF_BASE_URL}embeddinggemma-2-Q8_0.gguf?download=true",
            EmbedderModelSpec.MODEL.url,
        )
        assertEquals(309_855_456L, EmbedderModelSpec.MODEL.sizeBytes)
        assertEquals(
            "2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135",
            EmbedderModelSpec.MODEL.sha256,
        )
    }

    @Test
    fun mmprojIsTheExactServedArtifact() {
        assertEquals("mmproj-embeddinggemma-2-Q8_0.gguf", EmbedderModelSpec.MMPROJ.fileName)
        assertEquals(
            "${EmbedderModelSpec.HF_BASE_URL}mmproj-embeddinggemma-2-Q8_0.gguf?download=true",
            EmbedderModelSpec.MMPROJ.url,
        )
        assertEquals(554_821_024L, EmbedderModelSpec.MMPROJ.sizeBytes)
    }

    @Test
    fun everyFileHasRealSizeAndSha() {
        EmbedderModelSpec.FILES.forEach { file ->
            assertTrue(file.sizeBytes > 0L)
            assertEquals(64, file.sha256.length)
            assertTrue(file.sha256.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun totalBytesIsTheSumOfBothFiles() {
        assertEquals(
            EmbedderModelSpec.MODEL.sizeBytes + EmbedderModelSpec.MMPROJ.sizeBytes,
            EmbedderModelSpec.TOTAL_BYTES,
        )
    }

    @Test
    fun singleFileAliasesDescribeTheWeights() {
        assertEquals(EmbedderModelSpec.MODEL.fileName, EmbedderModelSpec.FILE_NAME)
        assertEquals(EmbedderModelSpec.MODEL.sizeBytes, EmbedderModelSpec.APPROX_BYTES)
        assertEquals(EmbedderModelSpec.MODEL.url, EmbedderModelSpec.DOWNLOAD_URL)
        assertEquals(EmbedderModelSpec.MODEL.sha256, EmbedderModelSpec.SHA256)
    }

    @Test
    fun embeddingDimensionIsSevenSixtyEight() {
        assertEquals(768, EmbedderModelSpec.EMBEDDING_DIM)
    }

    @Test
    fun modelNameIsSet() {
        assertEquals("EmbeddingGemma 2 (Q8_0)", EmbedderModelSpec.NAME)
    }

    // --- shared policy delegation -----------------------------------------------------------

    @Test
    fun freeSpaceDecisionDelegatesToSharedPolicy() {
        for (free in listOf(0L, 999L, 1_000L, 1_249L, 1_250L, 10_000L)) {
            assertEquals(
                ModelDownloadPolicy.freeSpaceDecision(free, 1_000L),
                EmbedderModelSpec.freeSpaceDecision(free, 1_000L),
            )
        }
    }

    @Test
    fun requiredWithMarginIsOneAndAQuarter() {
        assertEquals(1_250L, EmbedderModelSpec.requiredWithMargin(1_000L))
    }

    @Test
    fun progressPercentDelegatesToSharedPolicy() {
        assertEquals(0, EmbedderModelSpec.progressPercent(0L, 1_000L))
        assertEquals(50, EmbedderModelSpec.progressPercent(500L, 1_000L))
        assertEquals(100, EmbedderModelSpec.progressPercent(2_000L, 1_000L))
    }

    @Test
    fun freeSpaceMessageDelegatesToSharedPolicy() {
        assertEquals(
            ModelDownloadPolicy.freeSpaceMessage(FreeSpaceDecision.LOW, 1_000L, 1_000L),
            EmbedderModelSpec.freeSpaceMessage(FreeSpaceDecision.LOW, 1_000L, 1_000L),
        )
    }
}
