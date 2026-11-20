package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BM25 + cosine fusion in [MemoryRanker] and its honest degradation. When no vectors
 * are supplied the ranking and the scores must be IDENTICAL to the pre-embedding behaviour; when a
 * query vector and candidate vectors exist, semantic similarity may reorder genuine lexical matches.
 */
class MemoryRankerFusionTest {

    private fun memory(id: Long, text: String) =
        MemoryRecord(id = id, text = text, createdAt = 0L, lastUsedAt = 0L)

    @Test
    fun missingVectorsKeepExactlyTheLexicalRanking() {
        val memories = listOf(memory(1, "чай чай"), memory(2, "чай кофе"))
        val baseline = MemoryRanker.rank("чай", memories)
        val explicitNull = MemoryRanker.rank("чай", memories, queryVector = null, docVectors = emptyMap())
        val noDocVectors = MemoryRanker.rank(
            "чай", memories, queryVector = floatArrayOf(1f, 0f), docVectors = mapOf("1" to null, "2" to null)
        )
        assertEquals(baseline, explicitNull)
        assertEquals(baseline, noDocVectors)
        assertEquals(1L, baseline.first().id)
    }

    @Test
    fun cosineCanRerankLexicalMatchesButTheSetIsUnchanged() {
        val memories = listOf(memory(1, "чай чай"), memory(2, "чай кофе"))
        val fused = MemoryRanker.rank(
            query = "чай",
            memories = memories,
            queryVector = floatArrayOf(1f, 0f),
            docVectors = mapOf(
                "1" to floatArrayOf(0f, 1f),
                "2" to floatArrayOf(1f, 0f)
            )
        )
        assertEquals("the semantically closer memory must win", 2L, fused.first().id)
        assertEquals("both memories remain matched", setOf(1L, 2L), fused.map { it.id }.toSet())
        assertEquals("all hits stay lexically matched", 2, fused.count { it.lexicallyMatched })
    }

    @Test
    fun fusionWeightsAreDocumentedConstants() {
        assertEquals(0.6, MemoryRanker.FUSION_LEXICAL_WEIGHT, 0.0)
        assertEquals(0.4, MemoryRanker.FUSION_EMBEDDING_WEIGHT, 0.0)
    }
}
