package com.andharness.ru.data.tools.skill

import android.view.accessibility.AccessibilityNodeInfo
import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.ui.AccessibilityTree
import com.andharness.ru.domain.skill.NodeHitTest
import com.andharness.ru.domain.skill.RecordedNode
import com.andharness.ru.domain.skill.RecordedSkillAction
import com.andharness.ru.domain.skill.SkillCodec
import com.andharness.ru.domain.skill.SkillRecordingConverter
import com.andharness.ru.domain.ui.UiNode
import com.andharness.ru.service.AccessibilityAutomationService
import kotlinx.serialization.json.JsonObject

/**
 * Bounded, process-wide recorder for turning a live session into an app skill (M2 slice 3, SPEC
 * §24.2). It is fed by `UiAutomationToolsHandler.recordIfActive` for every `ui_*` action while a
 * `skill_record_start` session is active, exactly like the UI-macro recorder — but it ALSO captures
 * the semantics of the target at record time (the node under a tapped coordinate, the focused input,
 * the scrollable container), so [SkillRecordingConverter] can build a semantic `SkillStep` instead of
 * a brittle coordinate tap.
 *
 * Recording is OFF by default and bounded by [SkillCodec.MAX_STEPS]; once the cap is hit further
 * actions are ignored and [Snapshot.overflow] is set so `skill_record_stop` can report it honestly.
 * Every capture is best-effort: a null service or a missing node never fails the recorded action.
 *
 * Reference: https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
 */
object SkillActionCapture {

    /** The state handed over by [stop]. */
    data class Snapshot(
        val actions: List<RecordedSkillAction>,
        val overflow: Boolean,
        /** The package the caller asked to annotate (may be blank). */
        val requestedPackage: String,
        /** The foreground package read when recording started; `null` when the service was off. */
        val foregroundPackage: String?,
    )

    private val lock = Any()
    private var active = false
    private var requestedPackage: String? = null
    private var foregroundPackage: String? = null
    private var actions = ArrayList<RecordedSkillAction>()
    private var overflow = false

    fun isRecording(): Boolean = synchronized(lock) { active }

    /**
     * Starts a fresh recording, dropping any previous one. Reads the current foreground package so the
     * caller can annotate the skill even when `appPackage` was not supplied. Returns that package.
     */
    fun start(appPackage: String?): String? = synchronized(lock) {
        requestedPackage = appPackage
        foregroundPackage = readForegroundPackage()
        actions = ArrayList()
        overflow = false
        active = true
        foregroundPackage
    }

    /** Best-effort capture of one action (never throws). No-op when no session is active. */
    fun record(tool: String, arguments: JsonObject) {
        synchronized(lock) {
            if (!active) return
            if (actions.size >= SkillCodec.MAX_STEPS) {
                overflow = true
                return
            }
            val node = runCatching { captureNode(tool, arguments) }.getOrNull()
            actions += RecordedSkillAction(tool, arguments.toString(), node)
        }
    }

    /** Stops recording; returns the snapshot, or null when no session was active. */
    fun stop(): Snapshot? = synchronized(lock) {
        if (!active) return null
        active = false
        Snapshot(
            actions = actions.toList(),
            overflow = overflow,
            requestedPackage = requestedPackage.orEmpty(),
            foregroundPackage = foregroundPackage,
        )
    }

    private fun readForegroundPackage(): String? =
        AccessibilityAutomationService.instance?.rootInActiveWindow?.packageName?.toString()

    /**
     * Captures the semantics of the target the action will touch. Only actions with a resolvable
     * target are enriched; everything else returns `null` and is converted by selector or skipped.
     */
    private fun captureNode(tool: String, args: JsonObject): RecordedNode? {
        val service = AccessibilityAutomationService.instance ?: return null
        return when (tool) {
            "ui_tap", "ui_long_press" -> {
                val x = args.str("x")?.toIntOrNull()
                val y = args.str("y")?.toIntOrNull()
                if (x == null || y == null) return null
                val tree = service.rootInActiveWindow?.let { AccessibilityTree.toUiNode(it) } ?: return null
                NodeHitTest.findAt(tree, x, y)?.toRecordedNode()
            }

            "ui_type" -> service.rootInActiveWindow
                ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.toRecordedNode()

            "ui_scroll" -> findScrollable(service.rootInActiveWindow)?.toRecordedNode()

            else -> null
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i))?.let { return it }
        return null
    }

    private fun AccessibilityNodeInfo.toRecordedNode(): RecordedNode? {
        val text = text?.toString()?.takeIf { it.isNotBlank() }
        val description = contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val resourceId = viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val className = className?.toString()?.takeIf { it.isNotBlank() }
        if (text == null && description == null && resourceId == null) return null
        return RecordedNode(
            text = text,
            contentDescription = description,
            resourceId = resourceId,
            className = className,
        )
    }

    private fun UiNode.toRecordedNode(): RecordedNode? {
        val text = text?.takeIf { it.isNotBlank() }
        val description = contentDescription?.takeIf { it.isNotBlank() }
        val resourceId = resourceId?.takeIf { it.isNotBlank() }
        val className = className?.takeIf { it.isNotBlank() }
        if (text == null && description == null && resourceId == null) return null
        return RecordedNode(
            text = text,
            contentDescription = description,
            resourceId = resourceId,
            className = className,
        )
    }
}
