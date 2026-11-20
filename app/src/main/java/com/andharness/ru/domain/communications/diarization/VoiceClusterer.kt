package com.andharness.ru.domain.communications.diarization

/**
 * Pure, JVM-testable speaker diarisation: groups per-segment voice embeddings into speaker clusters
 * ([cluster]) and matches those clusters to the existing speaker registry ([matchToRegistry]).
 *
 * ## Why average-linkage agglomerative clustering over cosine
 *
 * Each observation starts in its own cluster; at every step the two clusters with the **highest
 * average cosine similarity** across all their member pairs are merged, until the best remaining
 * pair falls below [threshold]. Voice embeddings are L2-normalised, so cosine similarity is the
 * standard comparison ("distance is invariant to global scalings of the signal" —
 * scikit-learn, *Clustering* §2.3.6.4); **average linkage** is preferred over single linkage
 * because single linkage chains noisy samples together, and over complete linkage/Ward because the
 * metric here is cosine, not Euclidean, and Ward cannot vary its affinity
 * (scikit-learn, *Clustering* §2.3.6 and §2.3.6.1).
 *
 * The implementation is deliberately greedy and deterministic: pairs are scanned in
 * `(i, j)` index order and merges keep the lower-indexed cluster, so the result is **order-stable**
 * for a given input order and independent of hash iteration. Singletons that never reach
 * [threshold] stay as their own cluster; empty input yields an empty list.
 *
 * ## Limits (honest notes)
 *
 * - **Thresholds are provisional.** [DEFAULT_CLUSTER_THRESHOLD] / [DEFAULT_MATCH_THRESHOLD] are
 *   conservative starting points, not tuned values; they must be calibrated on-device against real
 *   recordings, because the right cut-off depends on the embedding model and microphone.
 * - **Greedy, O(n^3)** for `n` segments: fine for a single recording's segment list, not for
 *   documents with thousands of segments.
 * - No speaker-count prior, no duration weighting, and no re-clustering after a merge beyond the
 *   average linkage itself. Segment overlap/short segments are not modelled.
 *
 * References:
 * - Agglomerative clustering & linkage criteria (average linkage minimises the average distance
 *   between all observations of pairs of clusters; cosine distance is supported) —
 *   https://scikit-learn.org/stable/modules/clustering.html#hierarchical-clustering
 * - `sklearn.cluster.AgglomerativeClustering` —
 *   https://scikit-learn.org/stable/modules/generated/sklearn.cluster.AgglomerativeClustering.html
 * - Cosine similarity — https://en.wikipedia.org/wiki/Cosine_similarity
 */
object VoiceClusterer {

    /**
     * Minimum average cosine similarity for two clusters to be merged. Provisional conservative
     * default; tune on-device.
     */
    const val DEFAULT_CLUSTER_THRESHOLD: Float = 0.70f

    /**
     * Minimum cosine similarity of a cluster centroid to a registry centroid for the cluster to be
     * attributed to that speaker. Provisional conservative default; tune on-device.
     */
    const val DEFAULT_MATCH_THRESHOLD: Float = 0.75f

    /** One diarisation input: a transcript segment and its voice embedding. */
    data class SegmentEmbedding(
        val segmentId: Long,
        val vector: FloatArray,
    )

    /** One discovered speaker cluster: its centroid and the segments assigned to it. */
    data class DiarizationCluster(
        val clusterIndex: Int,
        val centroid: FloatArray,
        val segmentIds: List<Long>,
    )

    /**
     * One registry speaker reduced to the fields matching needs: its [speakerId], its current
     * [centroid] and how many samples fed that centroid ([sampleCount], `0` when unknown) so a new
     * cluster can be folded in as a count-weighted running mean.
     */
    data class RegistrySpeaker(
        val speakerId: Long,
        val centroid: FloatArray,
        val sampleCount: Int = 0,
    )

    /**
     * The registry link chosen for a cluster. [speakerId] is `null` when the cluster is a new
     * speaker (its best similarity was below [threshold], the registry was empty, or a closer
     * cluster claimed the only candidate). [similarity] is the best cosine similarity observed for
     * the cluster (to the assigned speaker when matched; otherwise to its best candidate, or `0`
     * when there are no usable registry centroids), so callers can inspect near-misses.
     */
    data class SpeakerAssignment(
        val clusterIndex: Int,
        val speakerId: Long?,
        val similarity: Float,
    )

    /**
     * Groups [items] into speaker clusters with greedy average-linkage agglomerative clustering
     * over cosine similarity. Clusters are returned ordered by their first (lowest-index) input
     * item and numbered `0..k-1`; segment ids inside a cluster keep input order. Empty input → empty
     * list. Pure and deterministic for a given input order.
     */
    fun cluster(
        items: List<SegmentEmbedding>,
        threshold: Float = DEFAULT_CLUSTER_THRESHOLD,
    ): List<DiarizationCluster> {
        if (items.isEmpty()) return emptyList()

        val vectors = items.map { VoiceVectorMath.l2Normalize(it.vector) }
        val groups = items.indices.map { mutableListOf(it) }.toMutableList()

        while (groups.size > 1) {
            var bestI = -1
            var bestJ = -1
            var bestSimilarity = Float.NEGATIVE_INFINITY
            for (i in groups.indices) {
                for (j in i + 1 until groups.size) {
                    val similarity = averageLinkage(vectors, groups[i], groups[j])
                    if (similarity > bestSimilarity) {
                        bestSimilarity = similarity
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (bestI < 0 || bestSimilarity < threshold) break
            groups[bestI].addAll(groups[bestJ])
            groups.removeAt(bestJ)
        }

        return groups.mapIndexed { index, members ->
            val centroid = VoiceVectorMath.l2Normalize(
                VoiceVectorMath.mean(members.map { vectors[it] })
            )
            DiarizationCluster(
                clusterIndex = index,
                centroid = centroid,
                segmentIds = members.map { items[it].segmentId },
            )
        }
    }

    /**
     * Assigns each cluster its best registry speaker whose centroid similarity is at least
     * [threshold], else `null` (new speaker). A registry speaker is handed to **at most one**
     * cluster: when several clusters claim it, the one with the highest similarity wins (ties go to
     * the lower cluster index); the losing clusters become new speakers rather than falling back to
     * a second-best registry speaker, which is the conservative choice for attributing speech to a
     * person. Assignments are returned for every cluster, in cluster order.
     */
    fun matchToRegistry(
        clusters: List<DiarizationCluster>,
        registry: List<RegistrySpeaker>,
        threshold: Float = DEFAULT_MATCH_THRESHOLD,
    ): List<SpeakerAssignment> {
        if (clusters.isEmpty()) return emptyList()

        val claimedRegistry = IntArray(clusters.size) { NO_REGISTRY }
        val bestSimilarity = FloatArray(clusters.size)

        for ((clusterPosition, cluster) in clusters.withIndex()) {
            var bestIndex = NO_REGISTRY
            var best = Float.NEGATIVE_INFINITY
            for ((registryIndex, speaker) in registry.withIndex()) {
                val similarity = VoiceVectorMath.cosine(cluster.centroid, speaker.centroid)
                if (bestIndex == NO_REGISTRY || similarity > best) {
                    best = similarity
                    bestIndex = registryIndex
                }
            }
            if (bestIndex != NO_REGISTRY && best >= threshold) {
                claimedRegistry[clusterPosition] = bestIndex
                bestSimilarity[clusterPosition] = best
            } else {
                claimedRegistry[clusterPosition] = NO_REGISTRY
                bestSimilarity[clusterPosition] = if (bestIndex != NO_REGISTRY) best else 0f
            }
        }

        val winnerForRegistry = mutableMapOf<Int, Int>()
        for (clusterPosition in clusters.indices) {
            val registryIndex = claimedRegistry[clusterPosition]
            if (registryIndex == NO_REGISTRY) continue
            val currentWinner = winnerForRegistry[registryIndex]
            if (currentWinner == null || bestSimilarity[clusterPosition] > bestSimilarity[currentWinner]) {
                winnerForRegistry[registryIndex] = clusterPosition
            }
        }

        return clusters.mapIndexed { clusterPosition, cluster ->
            val registryIndex = claimedRegistry[clusterPosition]
            val assignedSpeakerId =
                if (registryIndex != NO_REGISTRY &&
                    winnerForRegistry[registryIndex] == clusterPosition
                ) {
                    registry[registryIndex].speakerId
                } else {
                    null
                }
            SpeakerAssignment(
                clusterIndex = cluster.clusterIndex,
                speakerId = assignedSpeakerId,
                similarity = bestSimilarity[clusterPosition],
            )
        }
    }

    /** Mean cosine similarity over every member pair across [a] and [b] (average linkage). */
    private fun averageLinkage(
        vectors: List<FloatArray>,
        a: List<Int>,
        b: List<Int>,
    ): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var sum = 0f
        var count = 0
        for (i in a) {
            for (j in b) {
                sum += VoiceVectorMath.cosine(vectors[i], vectors[j])
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private const val NO_REGISTRY = -1
}
