package com.andharness.ru.domain.communications.diarization

/**
 * Pure, JVM-testable decision glue between [VoiceClusterer] and persistence: turns clustered voice
 * embeddings plus their registry matches into concrete speaker updates the data layer can execute.
 *
 * For every cluster [plan] decides whether it belongs to an existing registry speaker (the
 * [VoiceClusterer.SpeakerAssignment] carries a non-null id) or is a new one:
 * - **matched:** the speaker keeps its id, its centroid becomes the count-weighted running mean of
 *   the old centroid and the cluster centroid, and its sample count grows by the cluster size;
 * - **new:** the update carries no id (`speakerId = null`), the cluster centroid and its size become
 *   the speaker's initial centroid/count.
 *
 * A matched speaker whose stored centroid is empty or has a different dimension (e.g. after a model
 * change) cannot be merged honestly, so the update keeps the id but replaces the centroid with the
 * fresh cluster and resets the count to the cluster size — no incompatible vectors are averaged.
 *
 * No Android/Room/coroutines here: the caller does the I/O and this stays unit-testable.
 *
 * References:
 * - Running mean / weighted average — https://en.wikipedia.org/wiki/Weighted_arithmetic_mean
 * - Cosine similarity of L2-normalised embeddings (why averaging centroids is meaningful) —
 *   https://en.wikipedia.org/wiki/Cosine_similarity
 */
object DiarizationPlanner {

    /**
     * One speaker update to persist.
     *
     * @param speakerId existing speaker id, or `null` to create a new speaker.
     * @param centroid the centroid to store (already L2-normalised).
     * @param sampleCount how many samples the centroid summarises after this update.
     * @param segmentIds transcript segments attributed to this speaker.
     */
    data class SpeakerUpdate(
        val speakerId: Long?,
        val centroid: FloatArray,
        val sampleCount: Int,
        val segmentIds: List<Long>,
    )

    /**
     * Builds one [SpeakerUpdate] per cluster, in cluster order. [assignments] is expected in the same
     * order as [clusters] (as [VoiceClusterer.matchToRegistry] returns them); a missing entry is
     * treated as a new speaker. Empty input yields an empty list.
     */
    fun plan(
        clusters: List<VoiceClusterer.DiarizationCluster>,
        assignments: List<VoiceClusterer.SpeakerAssignment>,
        registry: List<VoiceClusterer.RegistrySpeaker>,
    ): List<SpeakerUpdate> {
        if (clusters.isEmpty()) return emptyList()
        val registryById = registry.associateBy { it.speakerId }

        return clusters.mapIndexed { index, cluster ->
            val matched = assignments.getOrNull(index)?.speakerId?.let { registryById[it] }
            val clusterCount = cluster.segmentIds.size
            if (matched == null) {
                return@mapIndexed SpeakerUpdate(
                    speakerId = null,
                    centroid = VoiceVectorMath.l2Normalize(cluster.centroid),
                    sampleCount = clusterCount,
                    segmentIds = cluster.segmentIds,
                )
            }

            val mergeable = matched.centroid.isNotEmpty() &&
                matched.centroid.size == cluster.centroid.size
            val merged = if (mergeable) {
                VoiceVectorMath.weightedMean(
                    a = matched.centroid,
                    weightA = matched.sampleCount,
                    b = cluster.centroid,
                    weightB = clusterCount,
                ).takeIf { it.isNotEmpty() }
            } else {
                null
            }

            if (merged != null) {
                SpeakerUpdate(
                    speakerId = matched.speakerId,
                    centroid = VoiceVectorMath.l2Normalize(merged),
                    sampleCount = matched.sampleCount.coerceAtLeast(0) + clusterCount,
                    segmentIds = cluster.segmentIds,
                )
            } else {
                SpeakerUpdate(
                    speakerId = matched.speakerId,
                    centroid = VoiceVectorMath.l2Normalize(cluster.centroid),
                    sampleCount = clusterCount,
                    segmentIds = cluster.segmentIds,
                )
            }
        }
    }
}
