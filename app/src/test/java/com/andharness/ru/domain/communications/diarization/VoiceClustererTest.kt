package com.andharness.ru.domain.communications.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [VoiceClusterer] and [VoiceVectorMath]. No Android, no Room, no coroutines.
 *
 * The vectors are tiny hand-written stand-ins for 256-dim WeSpeaker embeddings: what matters for
 * the algorithm is the angle between them, not the dimension.
 */
class VoiceClustererTest {

    // --- helpers ----------------------------------------------------------------------------

    private fun v(vararg values: Float) = values

    private fun segment(id: Long, vararg values: Float) =
        VoiceClusterer.SegmentEmbedding(id, values)

    /** Unit-length 2-D vector at the given cosine with `(1, 0)`. */
    private fun unitAtCosine(similarity: Float) = v(similarity, kotlin.math.sqrt(1f - similarity * similarity))

    private fun assertVectorsEqual(expected: FloatArray, actual: FloatArray, delta: Float = 1e-4f) {
        assertEquals("size", expected.size, actual.size)
        for (i in expected.indices) assertEquals("[$i]", expected[i], actual[i], delta)
    }

    // --- VoiceVectorMath --------------------------------------------------------------------

    @Test
    fun cosineIsOneForIdenticalVectorsAndZeroForOrthogonal() {
        assertEquals(1f, VoiceVectorMath.cosine(v(1f, 0f), v(1f, 0f)), 1e-6f)
        assertEquals(0f, VoiceVectorMath.cosine(v(1f, 0f), v(0f, 1f)), 1e-6f)
        assertEquals(-1f, VoiceVectorMath.cosine(v(1f, 0f), v(-1f, 0f)), 1e-6f)
    }

    @Test
    fun cosineIsScaleInvariantAndSafeForDegenerateInput() {
        assertEquals(1f, VoiceVectorMath.cosine(v(2f, 0f), v(9f, 0f)), 1e-6f)
        assertEquals(0f, VoiceVectorMath.cosine(FloatArray(0), FloatArray(0)), 1e-6f)
        assertEquals(0f, VoiceVectorMath.cosine(v(1f, 0f), v(1f, 0f, 0f)), 1e-6f)
        assertEquals(0f, VoiceVectorMath.cosine(v(0f, 0f), v(1f, 0f)), 1e-6f)
    }

    @Test
    fun meanAveragesComponentWiseAndSkipsWrongDimensions() {
        val mean = VoiceVectorMath.mean(listOf(v(1f, 0f), v(0f, 1f), v(9f)))
        assertVectorsEqual(v(0.5f, 0.5f), mean)
        assertVectorsEqual(FloatArray(0), VoiceVectorMath.mean(emptyList()))
    }

    @Test
    fun l2NormalizeProducesUnitLengthAndKeepsDegenerateInput() {
        assertVectorsEqual(v(0.6f, 0.8f), VoiceVectorMath.l2Normalize(v(3f, 4f)))
        assertVectorsEqual(FloatArray(0), VoiceVectorMath.l2Normalize(FloatArray(0)))
        assertVectorsEqual(v(0f, 0f), VoiceVectorMath.l2Normalize(v(0f, 0f)))
    }

    // --- clustering -------------------------------------------------------------------------

    @Test
    fun emptyInputYieldsNoClusters() {
        assertTrue(VoiceClusterer.cluster(emptyList()).isEmpty())
    }

    @Test
    fun identicalVectorsFormOneClusterKeepingInputOrder() {
        val clusters = VoiceClusterer.cluster(
            listOf(segment(1L, 1f, 0f), segment(2L, 1f, 0f), segment(3L, 1f, 0f))
        )
        assertEquals(1, clusters.size)
        assertEquals(listOf(1L, 2L, 3L), clusters[0].segmentIds)
        assertEquals(0, clusters[0].clusterIndex)
        assertVectorsEqual(v(1f, 0f), clusters[0].centroid)
    }

    @Test
    fun orthogonalVectorsEachStayInTheirOwnCluster() {
        val clusters = VoiceClusterer.cluster(
            listOf(segment(1L, 1f, 0f, 0f), segment(2L, 0f, 1f, 0f), segment(3L, 0f, 0f, 1f))
        )
        assertEquals(3, clusters.size)
        assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L)), clusters.map { it.segmentIds })
        assertEquals(listOf(0, 1, 2), clusters.map { it.clusterIndex })
    }

    @Test
    fun thresholdControlsWhetherSimilarVectorsMerge() {
        val items = listOf(segment(1L, 1f, 0f), segment(2L, *unitAtCosine(0.8f)))

        val merged = VoiceClusterer.cluster(items, threshold = 0.7f)
        assertEquals(1, merged.size)

        val split = VoiceClusterer.cluster(items, threshold = 0.85f)
        assertEquals(2, split.size)
    }

    @Test
    fun clustersAreOrderedByFirstAppearance() {
        // 10 and 30 are the same voice; 20 is a different one that appears between them.
        val clusters = VoiceClusterer.cluster(
            listOf(segment(10L, 1f, 0f), segment(20L, 0f, 1f), segment(30L, 1f, 0f))
        )
        assertEquals(2, clusters.size)
        assertEquals(listOf(10L, 30L), clusters[0].segmentIds)
        assertEquals(listOf(20L), clusters[1].segmentIds)
        assertEquals(listOf(0, 1), clusters.map { it.clusterIndex })
    }

    @Test
    fun clusteringIsDeterministicForTheSameInput() {
        val items = listOf(
            segment(1L, 1f, 0f),
            segment(2L, 0f, 1f),
            segment(3L, 1f, 0f),
            segment(4L, *unitAtCosine(0.9f)),
        )
        val first = VoiceClusterer.cluster(items)
        val second = VoiceClusterer.cluster(items)

        assertEquals(
            first.map { it.segmentIds },
            second.map { it.segmentIds },
        )
        first.zip(second).forEach { (a, b) ->
            assertEquals(a.clusterIndex, b.clusterIndex)
            assertVectorsEqual(a.centroid, b.centroid)
        }
    }

    @Test
    fun clusterCentroidIsTheNormalisedMeanOfItsMembers() {
        val clusters = VoiceClusterer.cluster(
            listOf(segment(1L, 1f, 0f), segment(2L, 1f, 0f), segment(3L, *unitAtCosine(0.8f)))
        )
        assertEquals(1, clusters.size)
        val expected = VoiceVectorMath.l2Normalize(
            VoiceVectorMath.mean(listOf(v(1f, 0f), v(1f, 0f), unitAtCosine(0.8f)))
        )
        assertVectorsEqual(expected, clusters[0].centroid)
        assertEquals(1f, VoiceVectorMath.cosine(clusters[0].centroid, clusters[0].centroid), 1e-5f)
    }

    // --- registry matching ------------------------------------------------------------------

    private fun cluster(index: Int, vararg values: Float) =
        VoiceClusterer.DiarizationCluster(index, values, listOf(index.toLong()))

    private fun registry(speakerId: Long, vararg values: Float) =
        VoiceClusterer.RegistrySpeaker(speakerId, values)

    @Test
    fun emptyClusterListYieldsNoAssignments() {
        assertTrue(VoiceClusterer.matchToRegistry(emptyList(), listOf(registry(1L, 1f, 0f))).isEmpty())
    }

    @Test
    fun clusterAboveThresholdIsAssignedToItsSpeaker() {
        val assignments = VoiceClusterer.matchToRegistry(
            listOf(cluster(0, 1f, 0f)),
            listOf(registry(7L, *unitAtCosine(0.99f))),
        )
        assertEquals(1, assignments.size)
        assertEquals(7L, assignments[0].speakerId)
        assertEquals(0, assignments[0].clusterIndex)
        assertTrue(assignments[0].similarity >= VoiceClusterer.DEFAULT_MATCH_THRESHOLD)
    }

    @Test
    fun clusterBelowThresholdIsANewSpeakerWithNearMissSimilarity() {
        val assignments = VoiceClusterer.matchToRegistry(
            listOf(cluster(0, 1f, 0f)),
            listOf(registry(7L, *unitAtCosine(0.5f))),
        )
        assertEquals(1, assignments.size)
        assertNull(assignments[0].speakerId)
        assertEquals(0.5f, assignments[0].similarity, 1e-4f)
    }

    @Test
    fun emptyRegistryMakesEveryClusterNewWithZeroSimilarity() {
        val assignments = VoiceClusterer.matchToRegistry(
            listOf(cluster(0, 1f, 0f), cluster(1, 0f, 1f)),
            emptyList(),
        )
        assertEquals(listOf(0, 1), assignments.map { it.clusterIndex })
        assertTrue(assignments.all { it.speakerId == null })
        assertTrue(assignments.all { it.similarity == 0f })
    }

    @Test
    fun oneRegistrySpeakerGoesToTheClosestClusterOnly() {
        val assignments = VoiceClusterer.matchToRegistry(
            listOf(
                cluster(0, 1f, 0f),
                cluster(1, *unitAtCosine(0.8f)),
            ),
            listOf(registry(7L, 1f, 0f)),
        )
        assertEquals(7L, assignments[0].speakerId)
        assertNull(assignments[1].speakerId)
        assertEquals(1f, assignments[0].similarity, 1e-4f)
        assertEquals(0.8f, assignments[1].similarity, 1e-4f)
    }

    @Test
    fun conflictTieGoesToTheLowerClusterIndex() {
        val assignments = VoiceClusterer.matchToRegistry(
            listOf(cluster(5, *unitAtCosine(0.9f)), cluster(6, *unitAtCosine(0.9f))),
            listOf(registry(7L, 1f, 0f)),
        )
        assertEquals(7L, assignments[0].speakerId)
        assertNull(assignments[1].speakerId)
        assertEquals(5, assignments[0].clusterIndex)
        assertEquals(6, assignments[1].clusterIndex)
    }

    @Test
    fun eachClusterCanMatchItsOwnDistinctSpeaker() {
        val assignments = VoiceClusterer.matchToRegistry(
            listOf(cluster(0, 1f, 0f), cluster(1, 0f, 1f)),
            listOf(registry(7L, 1f, 0f), registry(8L, 0f, 1f)),
        )
        assertEquals(listOf(7L, 8L), assignments.map { it.speakerId })
    }
}
