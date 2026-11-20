package com.andharness.ru.data.tools.ui

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.ui.UiSelector
import com.andharness.ru.domain.ui.UiSelectorResolver
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * `ui_macro_run` replay support: parsing stored step arguments, running one step through the shared
 * [UiActionEngine] action logic, and the honest pre-/post-conditions around it.
 *
 * The pre-condition checks the target in the tree read BEFORE the action (a tree read after the
 * action cannot prove the target was still there when it was acted on). The post-check is
 * informational for navigation steps and a real post-condition for `ui_type`.
 *
 * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow()
 */
internal class UiMacroReplay(
    private val engine: UiActionEngine,
    private val stepParser: Json,
) {

    /** Parses a stored step's raw JSON back into an argument object; null when it is malformed. */
    fun parseStepArgs(argsJson: String): JsonObject? = try {
        stepParser.parseToJsonElement(argsJson).jsonObject
    } catch (_: Exception) {
        null
    }

    /** Runs one replayed step through the shared action logic; null for an unknown tool name. */
    suspend fun runStep(
        service: AccessibilityService,
        tool: String,
        arguments: JsonObject,
    ): ToolResult? = when (tool) {
        "ui_tap" -> engine.tapAction(service, arguments)
        "ui_long_press" -> engine.longPressAction(service, arguments)
        "ui_swipe" -> engine.swipeAction(service, arguments)
        "ui_scroll" -> engine.scrollAction(service, arguments)
        "ui_type" -> engine.typeAction(service, arguments)
        "key_event" -> engine.keyEventAction(service, arguments)
        "ui_gesture_multi" -> engine.gestureMultiAction(service, arguments)
        else -> null
    }

    /**
     * Pre-condition for one replayed step: a step that carries a selector must resolve its target in
     * the CURRENT tree BEFORE the action is dispatched.
     *
     * Returns null when the step may run (a selector step whose target resolves, or a step with no
     * selector at all), otherwise a Russian description of what is missing.
     */
    suspend fun preconditionFailure(
        service: AccessibilityService,
        tool: String,
        arguments: JsonObject,
    ): String? {
        val selector = stepSelector(tool, arguments)
        if (selector.isEmpty) return null
        val root = rootWithRetry(service)
            ?: return "элемент (${selector.description}) не найден, сначала снимите ui_tree " +
                "(нет активного окна)"
        val tree = AccessibilityTree.toUiNode(root)
        val found = UiSelectorResolver.resolve(selector.selector, tree)
        return if (found != null) null
            else "элемент (${selector.description}) не найден, сначала снимите ui_tree"
    }

    /**
     * Honest post-condition for one replayed step, evaluated after [STEP_SETTLE_MS] so the tree
     * reflects the action's result.
     *
     * - steps without a selector (coordinates, swipe, scroll, global key, multitouch) have nothing
     *   to verify: the action's own success is the evidence;
     * - `ui_type` must show the typed value inside its target — a real, checkable post-condition;
     * - other selector steps must NOT require the target to persist: a tap/scroll legitimately
     *   navigates away and removes it. Presence is recorded (informational) but never required.
     *
     * The tree is read through [rootWithRetry] because the active window is transiently absent
     * during screen transitions. A null read there is intentionally NOT reported as "service
     * unavailable": `AccessibilityService.getRootInActiveWindow()` is `@Nullable` and returns null
     * when there is simply no currently active/readable window, which is a normal navigation moment,
     * not a disabled service (the replay loop re-checks [UiActionEngine.serviceOrNull] itself).
     * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow()
     */
    suspend fun postCheck(
        service: AccessibilityService,
        tool: String,
        arguments: JsonObject,
    ): StepPostCheck {
        val selector = stepSelector(tool, arguments)
        if (selector.isEmpty) return StepPostCheck()

        val root = rootWithRetry(service)
            ?: return postCheckDecision(
                tool = tool,
                rootAvailable = false,
                targetPresent = false,
                typeConfirmed = false,
            )
        val tree = AccessibilityTree.toUiNode(root)
        val target = UiSelectorResolver.resolve(selector.selector, tree)
        val typed = arguments.str("text")
        val confirmed = tool == "ui_type" && typed != null &&
            target?.node?.text?.contains(typed, ignoreCase = true) == true

        val decision = postCheckDecision(
            tool = tool,
            rootAvailable = true,
            targetPresent = target != null,
            typeConfirmed = confirmed,
        )
        // Keep the selector description in the honest post-condition message.
        return decision.failure?.let { decision.copy(failure = "$it (${selector.description})") }
            ?: decision
    }

    /**
     * Reads the active window root, absorbing transient nulls that occur during screen
     * transitions: `getRootInActiveWindow()` is `@Nullable` and legitimately returns null when no
     * window is currently active/readable. Retries a bounded number of times and returns the first
     * non-null root, or null if the window is still unavailable.
     *
     * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow()
     */
    private suspend fun rootWithRetry(service: AccessibilityService): AccessibilityNodeInfo? {
        repeat(ROOT_READ_ATTEMPTS) { attempt ->
            service.rootInActiveWindow?.let { return it }
            if (attempt < ROOT_READ_ATTEMPTS - 1) delay(ROOT_READ_DELAY_MS)
        }
        return null
    }

    /** The selector a replayed step carries; for `ui_type` `text` is the typed payload, not a selector. */
    private data class StepSelector(val selector: UiSelector) {
        val isEmpty: Boolean get() = selector.isEmpty
        val description: String get() = selector.description
    }

    /** Extracts a step's selector; `ui_type`'s `text` is excluded because it is the payload to type. */
    private fun stepSelector(tool: String, arguments: JsonObject): StepSelector =
        StepSelector(engine.selectorFrom(arguments, textIsSelector = tool != "ui_type"))

    /** Informational outcome of one replayed step's post-check; see [postCheck]. */
    data class StepPostCheck(
        val failure: String? = null,
        val hasSelector: Boolean = false,
        val targetPresent: Boolean = false,
        val typeConfirmed: Boolean = false,
    )

    internal companion object {
        /** Settle time between replayed macro steps, so the UI can react before the next one. */
        const val STEP_SETTLE_MS = 500L

        /** Bounded re-read attempts for the active window root before treating it as unavailable. */
        const val ROOT_READ_ATTEMPTS = 3

        /** Delay between root re-reads, absorbing transient nulls during screen transitions. */
        const val ROOT_READ_DELAY_MS = 100L
    }
}

/**
 * Pure post-check decision, independent of [AccessibilityService] so it is unit-testable on the JVM.
 *
 * The root being unavailable is handled differently per tool:
 * - for `ui_type` it is a legitimate failure — the typed value cannot be confirmed;
 * - for every other selector step the post-check is informational only (a tap/scroll may
 *   legitimately navigate away), so an unreadable tree must NOT be turned into a failure.
 *
 * When the root is available, `ui_type` requires the typed payload to be confirmed and other
 * selector steps only record target presence informationally.
 */
internal fun postCheckDecision(
    tool: String,
    rootAvailable: Boolean,
    targetPresent: Boolean,
    typeConfirmed: Boolean,
): UiMacroReplay.StepPostCheck {
    if (!rootAvailable) {
        return if (tool == "ui_type") {
            UiMacroReplay.StepPostCheck(
                failure = "не удалось прочитать дерево экрана — введённый текст не подтверждён"
            )
        } else {
            // Informational only: never abort a navigation/tap/scroll step on a transient null root.
            UiMacroReplay.StepPostCheck(hasSelector = true, targetPresent = false)
        }
    }
    if (tool == "ui_type") {
        return if (typeConfirmed) {
            UiMacroReplay.StepPostCheck(hasSelector = true, targetPresent = true, typeConfirmed = true)
        } else {
            UiMacroReplay.StepPostCheck(failure = "введённый текст не подтверждён в цели")
        }
    }
    return UiMacroReplay.StepPostCheck(hasSelector = true, targetPresent = targetPresent)
}
