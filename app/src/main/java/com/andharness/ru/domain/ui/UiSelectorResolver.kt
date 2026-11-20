package com.andharness.ru.domain.ui


/**
 * Resolves a [UiSelector] against a [UiNode] tree. Pure and JVM-testable: no Android types, no I/O.
 *
 * Ranking (deterministic): highest [UiMatch.score] first, then clickable nodes before
 * non-clickable ones, then document (pre-order) order. Specificity scores are:
 *
 * | criterion                    | points |
 * |------------------------------|--------|
 * | `text` exact (label)         | 50     |
 * | `resourceId`                 | 45     |
 * | `contentDescription` exact   | 35     |
 * | `textContains`               | 30     |
 * | `className` exact            | 25     |
 * | `contentDescription` partial | 20     |
 * | `className` partial          | 10     |
 *
 * A selector that specifies several criteria sums their points, so a node matching all of them
 * outranks a node matching only one.
 */
object UiSelectorResolver {

    private const val WEIGHT_TEXT_EXACT = 50
    private const val WEIGHT_RESOURCE_ID = 45
    private const val WEIGHT_DESC_EXACT = 35
    private const val WEIGHT_TEXT_CONTAINS = 30
    private const val WEIGHT_CLASS_EXACT = 25
    private const val WEIGHT_DESC_CONTAINS = 20
    private const val WEIGHT_CLASS_CONTAINS = 10

    /** Best-ranked match, honouring [UiSelector.index] (1-based) when it is set; `null` when none. */
    fun resolve(selector: UiSelector, root: UiNode?): UiMatch? {
        if (root == null || selector.isEmpty) return null
        val ranked = resolveAll(selector, root)
        val index = selector.index
        return if (index == null) ranked.firstOrNull() else ranked.getOrNull(index - 1)
    }

    /** Every match for [selector] in the [root] subtree, ranked (see the ranking rules above). */
    fun resolveAll(selector: UiSelector, root: UiNode?): List<UiMatch> {
        if (root == null || selector.isEmpty) return emptyList()
        val out = ArrayList<UiMatch>()
        walk(selector, root, emptyList(), out)
        // Stable sort: equal score+clickability keep their document (pre-order) position.
        return out.sortedWith(
            compareByDescending<UiMatch> { it.score }.thenByDescending { it.node.clickable }
        )
    }

    /**
     * The node to act on: [match]'s node when it is clickable, otherwise the nearest clickable
     * ancestor (deepest first). `null` when neither the node nor any ancestor is clickable — the
     * caller may then fall back to tapping the matched node's own bounds.
     */
    fun bestClickable(match: UiMatch): UiNode? {
        if (match.node.clickable) return match.node
        return match.ancestors.asReversed().firstOrNull { it.clickable }
    }

    private fun walk(
        selector: UiSelector,
        node: UiNode,
        ancestors: List<UiNode>,
        out: MutableList<UiMatch>,
    ) {
        score(selector, node)?.let { scored ->
            out += UiMatch(node = node, score = scored.score, reasons = scored.reasons, ancestors = ancestors)
        }
        val nextAncestors = ancestors + node
        for (child in node.children) walk(selector, child, nextAncestors, out)
    }

    private data class Scored(val score: Int, val reasons: List<String>)

    /** Returns the score + reasons when the node satisfies every non-blank criterion, else `null`. */
    private fun score(selector: UiSelector, node: UiNode): Scored? {
        var total = 0
        val reasons = ArrayList<String>()

        selector.text?.takeIf { it.isNotBlank() }?.let { q ->
            // Match the node's label: its visible text, or its contentDescription as a fallback.
            if (!node.labelEquals(q)) return null
            total += WEIGHT_TEXT_EXACT
            reasons += "text==«$q» +$WEIGHT_TEXT_EXACT"
        }
        selector.textContains?.takeIf { it.isNotBlank() }?.let { q ->
            if (!node.labelContains(q)) return null
            total += WEIGHT_TEXT_CONTAINS
            reasons += "text~«$q» +$WEIGHT_TEXT_CONTAINS"
        }
        selector.resourceId?.takeIf { it.isNotBlank() }?.let { q ->
            val id = node.resourceId ?: return null
            val short = q.substringAfterLast('/')
            if (!id.equals(short, ignoreCase = true)) return null
            total += WEIGHT_RESOURCE_ID
            reasons += "resourceId==«$short» +$WEIGHT_RESOURCE_ID"
        }
        selector.contentDescription?.takeIf { it.isNotBlank() }?.let { q ->
            val value = node.contentDescription ?: return null
            val exact = value.equals(q, ignoreCase = true)
            if (!exact && !value.contains(q, ignoreCase = true)) return null
            total += if (exact) WEIGHT_DESC_EXACT else WEIGHT_DESC_CONTAINS
            reasons += if (exact) "contentDescription==«$q» +$WEIGHT_DESC_EXACT"
            else "contentDescription~«$q» +$WEIGHT_DESC_CONTAINS"
        }
        selector.className?.takeIf { it.isNotBlank() }?.let { q ->
            val value = node.className ?: return null
            val exact = value.equals(q, ignoreCase = true)
            if (!exact && !value.contains(q, ignoreCase = true)) return null
            total += if (exact) WEIGHT_CLASS_EXACT else WEIGHT_CLASS_CONTAINS
            reasons += if (exact) "className==«$q» +$WEIGHT_CLASS_EXACT"
            else "className~«$q» +$WEIGHT_CLASS_CONTAINS"
        }

        if (reasons.isEmpty()) return null
        return Scored(total, reasons)
    }

    /** The node's display label: visible text, else its content description, else `null`. */
    private fun UiNode.label(): String? =
        text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }

    private fun UiNode.labelEquals(q: String): Boolean = label()?.equals(q, ignoreCase = true) == true

    private fun UiNode.labelContains(q: String): Boolean = label()?.contains(q, ignoreCase = true) == true
}
