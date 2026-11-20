package com.andharness.ru.domain.ui


/**
 * One ranked match: [node] is the matched node, [score] its specificity (higher is more specific),
 * [reasons] the per-criterion explanation of that score, and [ancestors] the chain from the tree
 * root down to (but excluding) [node], so [UiSelectorResolver.bestClickable] can walk upward.
 */
data class UiMatch(
    val node: UiNode,
    val score: Int,
    val reasons: List<String>,
    val ancestors: List<UiNode>,
)
