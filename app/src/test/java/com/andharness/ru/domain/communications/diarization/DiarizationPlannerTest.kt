package com.andharness.ru.domain.communications.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [DiarizationPlanner] and the [VoiceVectorMath.weightedMean] helper it relies on:
 * new-speaker creation, count-weighted centroid folding, and the incompatible-dimension fallback.
 */
class DiarizationPlannerTest {

    private fun v(vararg values: Float) = values

    private fun cluster(
        index: Int,
        segmentIds: List<Long>,
        vararg centroid: Float,
    ) = VoiceClusterer.DiarizationCluster(index, centroid, segmentIds)

    private fun registry(speakerId: Long, sampleCount: Int, vararg centroid: Float) =
        VoiceClusterer.RegistrySpeaker(speakerId, centroid, sampleCount)

    private fun assignment(clusterIndex: Int, speakerId: Long?) =
        VoiceClusterer.SpeakerAssignment(clusterIndex, speakerId, similarity = 0f)

    private fun assertVectorsEqual(expected: FloatArray, actual: FloatArray, delta: Float = 1e-3f) {
        assertEquals("size", expected.size, actual.size)
        for (i in expected.indices) assertEquals("[$i]", expected[i], actual[i], delta)
    }

    // --- weightedMean -----------------------------------------------------------------------

    @Test
    fun weightedMeanAveragesWhenWeightsAreEqual() {
        assertVectorsEqual(v(0.5f, 0.5f), VoiceVectorMath.weightedMean(v(1f, 0f), 1, v(0f, 1f), 1))
    }

    @Test
    fun weightedMeanFollowsWeights() {
        // (1,0)*1 + (0,1)*3 => (0.25, 0.75)
        assertVectorsEqual(v(0.25f, 0.75f), VoiceVectorMath.weightedMean(v(1f, 0f), 1, v(0f, 1f), 3))
    }

    @Test
    fun weightedMeanWithZeroWeightReturnsTheOtherVector() {
        assertVectorsEqual(v(0f, 1f), VoiceVectorMath.weightedMean(v(1f, 0f), 0, v(0f, 1f), 5))
    }

    @Test
    fun weightedMeanRejectsMismatchedOrEmptyVectors() {
        assertVectorsEqual(FloatArray(0), VoiceVectorMath.weightedMean(v(1f, 0f), 1, v(1f, 0f, 0f), 1))
        assertVectorsEqual(FloatArray(0), VoiceVectorMath.weightedMean(FloatArray(0), 1, v(1f, 0f), 1))
    }

    // --- planner ----------------------------------------------------------------------------

    @Test
    fun emptyClustersYieldNoUpdates() {
        assertTrue(DiarizationPlanner.plan(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun unmatchedClusterBecomesANewSpeaker() {
        val updates = DiarizationPlanner.plan(
            clusters = listOf(cluster(0, listOf(1L, 2L), 1f, 0f)),
            assignments = listOf(assignment(0, null)),
            registry = emptyList(),
        )

        assertEquals(1, updates.size)
        assertNull(updates[0].speakerId)
        assertEquals(2, updates[0].sampleCount)
        assertEquals(listOf(1L, 2L), updates[0].segmentIds)
        assertVectorsEqual(v(1f, 0f), updates[0].centroid)
    }

    @Test
    fun matchedClusterFoldsCentroidBySampleCount() {
        // Existing speaker: centroid (1,0) from 1 sample; new cluster (0,1) from 3 segments.
        val updates = DiarizationPlanner.plan(
            clusters = listOf(cluster(0, listOf(1L, 2L, 3L), 0f, 1f)),
            assignments = listOf(assignment(0, 7L)),
            registry = listOf(registry(7L, sampleCount = 1, 1f, 0f)),
        )

        assertEquals(1, updates.size)
        assertEquals(7L, updates[0].speakerId)
        assertEquals(4, updates[0].sampleCount)
        // (1,0)*1 + (0,1)*3 = (0.25,0.75), normalised.
        val expected = VoiceVectorMath.l2Normalize(v(0.25f, 0.75f))
        assertVectorsEqual(expected, updates[0].centroid)
    }

    @Test
    fun matchedSameDirectionCentroidKeepsDirection() {
        val updates = DiarizationPlanner.plan(
            clusters = listOf(cluster(0, listOf(9L), 3f, 4f)),
            assignments = listOf(assignment(0, 7L)),
            registry = listOf(registry(7L, sampleCount = 2, 6f, 8f)),
        )

        assertEquals(7L, updates[0].speakerId)
        assertEquals(3, updates[0].sampleCount)
        assertVectorsEqual(v(0.6f, 0.8f), updates[0].centroid)
    }

    @Test
    fun matchedClusterWithIncompatibleDimensionReplacesCentroid() {
        val updates = DiarizationPlanner.plan(
            clusters = listOf(cluster(0, listOf(1L, 2L), 1f, 0f)),
            assignments = listOf(assignment(0, 7L)),
            registry = listOf(registry(7L, sampleCount = 5, 1f, 0f, 0f)),
        )

        assertEquals(7L, updates[0].speakerId)
        assertEquals(2, updates[0].sampleCount)
        assertVectorsEqual(v(1f, 0f), updates[0].centroid)
    }

    @Test
    fun missingAssignmentEntryIsTreatedAsNewSpeaker() {
        val updates = DiarizationPlanner.plan(
            clusters = listOf(cluster(0, listOf(1L, 2L), 0f, 1f)),
            assignments = emptyList(),
            registry = listOf(registry(7L, sampleCount = 1, 1f, 0f)),
        )

        assertNull(updates[0].speakerId)
        assertEquals(2, updates[0].sampleCount)
    }
}
