package com.andharness.ru.data.index

import com.andharness.ru.data.index.DeviceSearchRanker.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [DeviceSearchRanker] — the cosine top-K that [DeviceSearchService] runs over
 * the whole-device index. Fake [Candidate]s stand in for indexed rows, so no Room/Android is needed.
 */
class DeviceSearchRankerTest {

    private val query = floatArrayOf(1f, 0f)

    private fun candidate(
        name: String,
        vector: FloatArray,
        kind: DeviceIndexKind = DeviceIndexKind.DOC_CHUNK,
        chunk: Int = 0,
    ): Candidate = Candidate(
        kind = kind,
        sourceUri = "content://$name",
        displayName = name,
        snippet = "snippet of $name",
        mimeType = "text/plain",
        chunkIndex = chunk,
        vector = vector,
    )

    @Test
    fun cosineOfUnitVectorsIsTheirDotProduct() {
        assertEquals(1.0, DeviceSearchRanker.cosine(query, floatArrayOf(1f, 0f)), 1e-6)
        assertEquals(0.0, DeviceSearchRanker.cosine(query, floatArrayOf(0f, 1f)), 1e-6)
        // [1,0] · [0.6,0.8] = 0.6
        assertEquals(0.6, DeviceSearchRanker.cosine(query, floatArrayOf(0.6f, 0.8f)), 1e-6)
    }

    @Test
    fun cosineIsZeroForEmptyMismatchedOrNonFiniteInput() {
        assertEquals(0.0, DeviceSearchRanker.cosine(FloatArray(0), floatArrayOf(1f, 0f)), 1e-6)
        assertEquals(0.0, DeviceSearchRanker.cosine(query, FloatArray(0)), 1e-6)
        assertEquals(0.0, DeviceSearchRanker.cosine(query, floatArrayOf(1f, 0f, 0f)), 1e-6)
        assertEquals(0.0, DeviceSearchRanker.cosine(query, floatArrayOf(Float.NaN, 0f)), 1e-6)
    }

    @Test
    fun topKReturnsBestFirstAndRespectsTheLimit() {
        val best = candidate("best", floatArrayOf(1f, 0f))
        val middle = candidate("middle", floatArrayOf(0.8f, 0.6f))
        val zero = candidate("zero", floatArrayOf(0f, 1f))

        val all = DeviceSearchRanker.topK(query, listOf(zero, best, middle), limit = 3)

        assertEquals(listOf("best", "middle"), all.map { it.displayName })
        assertTrue(all.first().score > all.last().score)

        val limited = DeviceSearchRanker.topK(query, listOf(zero, best, middle), limit = 1)
        assertEquals(listOf("best"), limited.map { it.displayName })
    }

    @Test
    fun topKDropsNonPositiveScoresSoAnUnrelatedQueryIsEmpty() {
        val hits = DeviceSearchRanker.topK(
            query = query,
            candidates = listOf(
                candidate("orthogonal", floatArrayOf(0f, 1f)),
                candidate("opposite", floatArrayOf(-1f, 0f)),
            ),
            limit = 10,
        )
        assertTrue("only strictly-positive similarity may be returned", hits.isEmpty())
    }

    @Test
    fun topKIgnoresCandidatesWithAnEmptyVector() {
        val hits = DeviceSearchRanker.topK(
            query = query,
            candidates = listOf(
                candidate("broken", FloatArray(0)),
                candidate("real", floatArrayOf(1f, 0f)),
            ),
            limit = 10,
        )
        assertEquals(listOf("real"), hits.map { it.displayName })
    }

    @Test
    fun topKTiesAreBrokenDeterministicallyBySourceThenChunk() {
        val b = candidate("b", floatArrayOf(0.5f, 0.5f))
        val a = candidate("a", floatArrayOf(0.5f, 0.5f))
        val aChunk1 = candidate("a", floatArrayOf(0.5f, 0.5f), chunk = 1)

        val hits = DeviceSearchRanker.topK(query, listOf(b, a, aChunk1), limit = 3)

        assertEquals(
            listOf("content://a", "content://a", "content://b"),
            hits.map { it.sourceUri },
        )
        assertEquals(listOf(0, 1, 0), hits.map { it.chunkIndex })
    }

    @Test
    fun streamingTopKMatchesTheConvenienceOverAFullList() {
        val candidates = listOf(
            candidate("a", floatArrayOf(0.9f, 0.1f)),
            candidate("b", floatArrayOf(0.2f, 0.9f)),
            candidate("c", floatArrayOf(0.95f, 0.05f)),
            candidate("d", floatArrayOf(0.4f, 0.4f)),
        )

        val streaming = DeviceSearchRanker.TopK(query, limit = 2)
        candidates.take(2).forEach(streaming::offer)
        candidates.drop(2).forEach(streaming::offer)

        assertEquals(
            DeviceSearchRanker.topK(query, candidates, limit = 2).map { it.displayName },
            streaming.results().map { it.displayName },
        )
        assertEquals(listOf("c", "a"), streaming.results().map { it.displayName })
    }

    @Test
    fun resultCarriesTheCandidateIdentityAndScore() {
        val hit = DeviceSearchRanker.topK(
            query = query,
            candidates = listOf(candidate("img", floatArrayOf(0.7f, 0.3f), kind = DeviceIndexKind.IMAGE)),
            limit = 1,
        ).single()

        assertEquals(DeviceIndexKind.IMAGE, hit.kind)
        assertEquals("content://img", hit.sourceUri)
        assertEquals("img", hit.displayName)
        assertEquals("snippet of img", hit.snippet)
        assertEquals(0.7, hit.score, 1e-6)
    }
}
