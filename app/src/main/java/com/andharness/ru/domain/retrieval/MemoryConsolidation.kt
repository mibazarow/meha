package com.andharness.ru.domain.retrieval

object MemoryConsolidation {

    /**
     * Minimum distinct-token Jaccard overlap for two memories to be treated as the same fact.
     * 0.5 = half of the distinct content tokens are shared. An exact restatement scores 1.0, and a
     * negated restatement («любит кофе» vs «не любит кофе») also scores 1.0 because «не» is a
     * stop-word — so a contradiction is consolidated (the new text wins) instead of duplicated.
     */
    const val NEAR_DUPLICATE_JACCARD = 0.5

    /**
     * Picks the best-matching [existing] memory for the incoming text and returns the plan:
     *
     * - no memory reaches [NEAR_DUPLICATE_JACCARD]: [MemorySavePlan.Insert] with the trimmed input;
     * - otherwise [MemorySavePlan.Update] of the best match with the NEW text, the union of the old
     *   and new tags, `max(old, new)` importance and `lastUsedAt = [now]`.
     *
     * The result is deterministic: ties are broken by the order of [existing].
     */
    fun decide(
        existing: List<MemoryRecord>,
        text: String,
        tags: String,
        importance: Int,
        now: Long
    ): MemorySavePlan {
        val incoming = tokens(text)
        val best = existing
            .map { it to jaccard(incoming, tokens(it.text)) }
            .maxByOrNull { it.second }
        val match = best?.takeIf { it.first.text.isNotBlank() && it.second >= NEAR_DUPLICATE_JACCARD }
            ?: return MemorySavePlan.Insert(text.trim(), tags.trim(), importance.coerceIn(0, 2))
        return MemorySavePlan.Update(
            id = match.first.id,
            text = text.trim(),
            tags = mergeTags(match.first.tags, tags),
            importance = maxOf(match.first.importance, importance).coerceIn(0, 2),
            lastUsedAt = now
        )
    }

    /** Distinct token set of [text] used for the overlap (token frequency must not skew the ratio). */
    fun tokens(text: String): Set<String> = TextRetriever.tokenize(text).toSet()

    /** Jaccard similarity of two token sets; 0.0 when either side is empty. */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        val union = a.size + b.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    /**
     * Union of the old and new tag strings: comma/semicolon-separated, trimmed, de-duplicated
     * case-insensitively, old tags first, joined with ", ". Blank entries are dropped.
     */
    fun mergeTags(existing: String, incoming: String): String {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in (existing + "," + incoming).split(',', ';')) {
            val tag = raw.trim()
            if (tag.isEmpty() || !seen.add(tag.lowercase())) continue
            out += tag
        }
        return out.joinToString(", ")
    }
}
