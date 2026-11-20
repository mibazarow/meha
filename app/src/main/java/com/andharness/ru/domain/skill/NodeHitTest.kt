package com.andharness.ru.domain.skill

import com.andharness.ru.domain.ui.UiNode

/**
 * Pure hit-test used by the Android-bound recorder to capture the semantics of a coordinate action:
 * the deepest node whose bounds contain the point (the smallest-area candidate wins among overlapping
 * siblings). Kept here (over the Android-free [UiNode]) so it is JVM-testable.
 */
object NodeHitTest {

    /** Deepest node containing ([x], [y]); `null` when no node contains the point. */
    fun findAt(root: UiNode?, x: Int, y: Int): UiNode? {
        if (root == null) return null
        return find(root, x, y)
    }

    private fun find(node: UiNode, x: Int, y: Int): UiNode? {
        if (!contains(node, x, y)) return null
        var best: UiNode? = null
        for (child in node.children) {
            val match = find(child, x, y) ?: continue
            if (best == null || area(match) < area(best)) best = match
        }
        return best ?: node
    }

    private fun contains(node: UiNode, x: Int, y: Int): Boolean {
        val b = node.bounds
        return x >= b.left && x <= b.right && y >= b.top && y <= b.bottom &&
            b.right > b.left && b.bottom > b.top
    }

    private fun area(node: UiNode): Long {
        val b = node.bounds
        return (b.right - b.left).toLong() * (b.bottom - b.top).toLong()
    }
}
