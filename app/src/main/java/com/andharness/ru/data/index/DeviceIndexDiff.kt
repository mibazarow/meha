package com.andharness.ru.data.index

/**
 * Pure, JVM-testable incremental diff for the whole-device index.
 *
 * The indexer enumerates the CURRENT sources on the device (e.g. images from `MediaStore`) and already
 * INDEXED sources ([DeviceIndexRepository.sourceMetadataByKind], keyed by `sourceUri`). This object
 * computes the minimal plan between the two: which sources are new, which changed, which are gone, and
 * which are untouched — so a run never re-embeds an unchanged file.
 *
 * Change detection is file-level only ([Candidate.lastModified] + [Candidate.sizeBytes]), matching
 * [DeviceIndexSourceMeta]; a change to either field is treated as "re-index". Keeping this Android-free
 * means the whole add/update/delete decision is covered by plain JUnit tests without a device.
 */
object DeviceIndexDiff {

    /** One source currently present on the device, with the metadata used for change detection. */
    data class Candidate(
        val sourceUri: String,
        val lastModified: Long,
        val sizeBytes: Long,
    )

    /**
     * The plan for one pass. [toAdd] are brand-new sources, [toUpdate] are known sources whose
     * metadata changed, [toDelete] are indexed sources no longer present, and [unchanged] counts the
     * sources that are identical and must be skipped.
     */
    data class Plan(
        val toAdd: List<String>,
        val toUpdate: List<String>,
        val toDelete: List<String>,
        val unchanged: Int,
    ) {
        /** Everything that must be (re-)embedded this pass. */
        val toIndex: List<String> get() = toAdd + toUpdate

        val isNoOp: Boolean get() = toAdd.isEmpty() && toUpdate.isEmpty() && toDelete.isEmpty()
    }

    /**
     * Computes the [Plan] between the already-[known] index (keyed by `sourceUri`) and the [current]
     * device sources. A blank `sourceUri` is ignored (it can never be a valid index key). Order is
     * preserved: [Plan.toAdd]/[Plan.toUpdate] follow [current], [Plan.toDelete] follows the `known`
     * key order.
     */
    fun plan(
        known: Map<String, DeviceIndexSourceMeta>,
        current: List<Candidate>,
    ): Plan {
        val currentByUri = current.associateBy { it.sourceUri }
        val toAdd = ArrayList<String>()
        val toUpdate = ArrayList<String>()
        var unchanged = 0
        for (candidate in current) {
            val uri = candidate.sourceUri
            if (uri.isBlank()) continue
            val prior = known[uri]
            when {
                prior == null -> toAdd += uri
                prior.lastModified != candidate.lastModified ||
                    prior.sizeBytes != candidate.sizeBytes -> toUpdate += uri

                else -> unchanged++
            }
        }
        val toDelete = known.keys.filter { it !in currentByUri }
        return Plan(toAdd = toAdd, toUpdate = toUpdate, toDelete = toDelete, unchanged = unchanged)
    }
}
