package com.andharness.ru.data.tools.ui

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.andharness.ru.domain.ui.UiBounds
import com.andharness.ru.domain.ui.UiNode

/**
 * Shared accessibility-tree reader used by `ui_tree` and `screen_understand`, so both tools see
 * exactly the same elements. `ui_tree` renders [ScreenElement]s with [format]; the fusion tool
 * feeds the same rendering into the pure `ScreenFusion` parser.
 *
 * References (official Android docs):
 * - `AccessibilityNodeInfo` — https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
 * - `AccessibilityNodeInfo#getBoundsInScreen` —
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getBoundsInScreen(android.graphics.Rect)
 */
object AccessibilityTree {

    /** Hard cap on reported nodes, so a huge hierarchy can never blow up the answer. */
    const val MAX_NODES = 200

    /** Indentation is capped, so a deep hierarchy stays readable. */
    private const val MAX_INDENT_DEPTH = 12

    /**
     * Walks [root] depth-first and collects every reportable node, in reading order. Traversal
     * stops once [maxNodes] nodes have been collected (matching the historic `ui_tree` cap).
     */
    fun collect(root: AccessibilityNodeInfo?, maxNodes: Int = MAX_NODES): List<ScreenElement> {
        val out = ArrayList<ScreenElement>()
        walk(root, 0, maxNodes, out)
        return out
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        maxNodes: Int,
        out: MutableList<ScreenElement>,
    ) {
        if (node == null || out.size >= maxNodes) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val description = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val id = node.viewIdResourceName
        if (text != null || description != null || node.isClickable || node.isEditable || id != null) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            out += ScreenElement(
                depth = depth,
                text = text,
                description = description,
                resourceId = id,
                className = node.className?.toString(),
                clickable = node.isClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
            )
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, maxNodes, out)
    }

    /** Hard cap on the full-hierarchy traversal, so a runaway tree cannot blow up resolution. */
    const val MAX_HIERARCHY_NODES = 600

    /** Depth cap for the full-hierarchy traversal (mirrors the old `findInTree` depth gate). */
    const val MAX_HIERARCHY_DEPTH = 40

    /**
     * Converts the live [AccessibilityNodeInfo] hierarchy into the pure [UiNode] form the
     * `UiSelectorResolver` works on. Unlike [collect], NO node is filtered out: containers are kept
     * so a non-clickable label can be resolved up to its clickable ancestor, and every node keeps a
     * [UiNode.ref] handle back to the platform node so the caller can still `performAction`.
     *
     * Traversal is bounded by [maxDepth] and [maxNodes] so a pathological hierarchy cannot hang the
     * agent turn. Returns `null` only when [root] is `null`.
     *
     * References (official Android docs):
     * - `AccessibilityNodeInfo` (text, contentDescription, viewIdResourceName, className, isClickable):
     *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
     * - `getBoundsInScreen`:
     *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getBoundsInScreen(android.graphics.Rect)
     */
    fun toUiNode(
        root: AccessibilityNodeInfo?,
        maxDepth: Int = MAX_HIERARCHY_DEPTH,
        maxNodes: Int = MAX_HIERARCHY_NODES,
    ): UiNode? {
        val budget = intArrayOf(maxNodes)
        return toUiNodeInternal(root, 0, maxDepth, budget)
    }

    private fun toUiNodeInternal(
        node: AccessibilityNodeInfo?,
        depth: Int,
        maxDepth: Int,
        budget: IntArray,
    ): UiNode? {
        if (node == null || depth > maxDepth || budget[0] <= 0) return null
        budget[0]--
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val children = ArrayList<UiNode>()
        for (i in 0 until node.childCount) {
            toUiNodeInternal(node.getChild(i), depth + 1, maxDepth, budget)?.let { children += it }
        }
        return UiNode(
            text = node.text?.toString()?.takeIf { it.isNotBlank() },
            contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            resourceId = node.viewIdResourceName?.substringAfterLast('/'),
            className = node.className?.toString()?.takeIf { it.isNotBlank() },
            bounds = UiBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            children = children,
            ref = node,
        )
    }

    /**
     * Depth-first walk of [root] collecting each node's visible text in reading order: the node's
     * `text`, falling back to its `contentDescription` when the text is blank. Unlike [collect] this
     * keeps only the raw strings (no ids/coordinates/flags), which is what `remember_screen` saves.
     * Bounded by [maxNodes] so a pathological hierarchy cannot blow up the answer. Consecutive
     * duplicate/blank lines are removed later by the pure `ScreenRememberSupport.flatten`.
     *
     * References (official Android docs):
     * - `AccessibilityNodeInfo.getText()`:
     *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getText()
     * - `AccessibilityNodeInfo.getContentDescription()`:
     *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getContentDescription()
     */
    fun collectTexts(root: AccessibilityNodeInfo?, maxNodes: Int = MAX_NODES): List<String> {
        val out = ArrayList<String>()
        walkTexts(root, maxNodes, out)
        return out
    }

    private fun walkTexts(node: AccessibilityNodeInfo?, maxNodes: Int, out: MutableList<String>) {
        if (node == null || out.size >= maxNodes) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) out += text
        for (i in 0 until node.childCount) walkTexts(node.getChild(i), maxNodes, out)
    }

    /** Renders [elements] exactly like the historic `ui_tree` output. */
    fun format(elements: List<ScreenElement>): String {
        val sb = StringBuilder()
        for (element in elements) {
            sb.append("  ".repeat(element.depth.coerceAtMost(MAX_INDENT_DEPTH)))
            sb.append(element.label.take(80))
            element.resourceId?.let { sb.append(" #").append(it.substringAfterLast('/').take(40)) }
            if (element.clickable) sb.append(" [click]")
            if (element.editable) sb.append(" [edit]")
            if (element.scrollable) sb.append(" [scroll]")
            sb.append(" @").append(element.left).append(',').append(element.top)
                .append('-').append(element.right).append(',').append(element.bottom)
            sb.appendLine()
        }
        return sb.toString().trim()
    }
}
