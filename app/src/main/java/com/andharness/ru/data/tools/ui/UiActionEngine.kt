package com.andharness.ru.data.tools.ui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.andharness.ru.data.tools.skill.SkillActionCapture
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.ui.KeyEventMapper
import com.andharness.ru.domain.ui.UiMatch
import com.andharness.ru.domain.ui.UiNode
import com.andharness.ru.domain.ui.UiSelector
import com.andharness.ru.domain.ui.UiSelectorResolver
import com.andharness.ru.service.AccessibilityAutomationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume

/**
 * The recorded UI actions behind both the live `ui_*` tools and `ui_macro_run` replay: tap,
 * long-press, swipe, scroll, type, global keys and multitouch, plus the tree/selector plumbing
 * they share.
 *
 * The action bodies are replayed directly by `ui_macro_run` (never through the registry), so a
 * recorded step behaves identically to a fresh tool call. Every action records itself into
 * [recorder] while a recording is active; recording is best-effort and can never fail the action.
 *
 * The gesture/tap action family lives in `UiGestureActions.kt` and the find/wait/scroll-until
 * node family in `UiNodeActions.kt` as extension functions on this class; the shared helpers they
 * need are kept here.
 *
 * References (official Android docs):
 * - `AccessibilityNodeInfo` / `performAction`:
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
 * - `AccessibilityService.performGlobalAction`:
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#performGlobalAction(int)
 * - `dispatchGesture` + `GestureDescription`:
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler)
 */
internal class UiActionEngine(
    internal val context: Context,
    private val recorder: UiMacroRecorder,
    private val screenRecorder: UiScreenRecorder,
) {

    fun serviceOrNull(): AccessibilityAutomationService? = AccessibilityAutomationService.instance

    fun notEnabled(): ToolResult = ToolResult(
        false,
        com.andharness.ru.domain.permission.SpecialAccess.required(
            com.andharness.ru.domain.permission.SpecialAccess.ACCESSIBILITY
        )
    )

    /** Best-effort capture of one action into the active macro recording (never throws). */
    fun recordIfActive(tool: String, arguments: JsonObject) {
        runCatching { recorder.record(tool, arguments) }
        // The app-skill recorder (M2) observes the same actions independently; it also snapshots the
        // target node so a recorded action can be converted to a semantic skill step. Best-effort.
        runCatching { SkillActionCapture.record(tool, arguments) }
    }

    /** Best-effort screen-map observation; never affects the tool result. */
    fun autoRecordScreen(service: AccessibilityService) = screenRecorder.autoRecordScreen(service)

    suspend fun typeAction(service: AccessibilityService, arguments: JsonObject): ToolResult {
        val text = arguments.str("text")
            ?: return ToolResult(false, "Укажите text.")
        // `text` is the payload here, so a text-based target selector uses `selectorText`.
        val selector = selectorFrom(arguments, textIsSelector = false)
        val match = if (selector.isEmpty) null else resolveOnService(service, selector)
        val target = (match?.node?.ref as? AccessibilityNodeInfo)
            ?: (match?.let { UiSelectorResolver.bestClickable(it) }?.ref as? AccessibilityNodeInfo)
            ?: service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return ToolResult(
                false,
                "Поле ввода не найдено (снимите ui_tree и укажите selector: selectorText, " +
                    "contentDescription, resourceId, className)."
            )
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            ToolResult(true, "Ввёл текст в «${labelOf(target)}».")
        } else {
            ToolResult(false, "Не удалось ввести текст (элемент не редактируемый).")
        }
    }

    /**
     * `key_event`: maps the key name (via the pure [KeyEventMapper]) to a `GLOBAL_ACTION_*` and
     * performs it. The mapping owns the honest API-level guard (screenshot requires Android 14 /
     * API 34); the engine only turns the [KeyEventMapper.GlobalAction] enum into the platform
     * constant.
     *
     * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#performGlobalAction(int)
     */
    fun keyEventAction(service: AccessibilityService, arguments: JsonObject): ToolResult {
        val key = (arguments.str("key") ?: "").lowercase()
        return when (val resolution = KeyEventMapper.resolve(key, Build.VERSION.SDK_INT)) {
            is KeyEventMapper.Resolution.Send -> {
                val action = androidGlobalAction(resolution.action)
                if (service.performGlobalAction(action)) {
                    ToolResult(true, "Клавиша: $key")
                } else {
                    ToolResult(false, "Система отклонила действие «$key».")
                }
            }
            is KeyEventMapper.Resolution.Reject -> ToolResult(false, resolution.message)
        }
    }

    /**
     * `ui_copy`: copies the focused node's current selection to the clipboard via
     * `ACTION_COPY`. The node is the input-focused one (falling back to accessibility focus); a
     * `false` from `performAction` (no selection, unsupported node) is reported honestly rather
     * than faked.
     *
     * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#ACTION_COPY
     */
    fun copyAction(service: AccessibilityService): ToolResult =
        focusedNodeAction(service, AccessibilityNodeInfo.ACTION_COPY, "копирование")

    /**
     * `ui_paste`: pastes the clipboard into the focused (editable) node via `ACTION_PASTE`.
     *
     * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#ACTION_PASTE
     */
    fun pasteAction(service: AccessibilityService): ToolResult =
        focusedNodeAction(service, AccessibilityNodeInfo.ACTION_PASTE, "вставку")

    /**
     * `ui_cut`: cuts the focused node's current selection to the clipboard via `ACTION_CUT`.
     *
     * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#ACTION_CUT
     */
    fun cutAction(service: AccessibilityService): ToolResult =
        focusedNodeAction(service, AccessibilityNodeInfo.ACTION_CUT, "вырезание")

    /**
     * `ui_select_all`: selects the whole text of the focused node.
     *
     * `AccessibilityNodeInfo` has no public `ACTION_SELECT_ALL`; the documented way to select a
     * range is `ACTION_SET_SELECTION` with the `ACTION_ARGUMENT_SELECTION_START_INT`/
     * `ACTION_ARGUMENT_SELECTION_END_INT` arguments. Selecting `0..text.length` is therefore
     * "select all". The length comes from the node's own text, so a node that exposes no text (or
     * rejects the action) yields an honest failure instead of a fake success.
     *
     * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#ACTION_SET_SELECTION
     * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#ACTION_ARGUMENT_SELECTION_START_INT
     */
    fun selectAllAction(service: AccessibilityService): ToolResult {
        val node = focusedNode(service)
            ?: return ToolResult(
                false,
                "Нет узла в фокусе: сначала установите фокус на текстовое поле. Выделение всего текста не выполнено."
            )
        val text = node.text?.toString()
        if (text.isNullOrEmpty()) {
            return ToolResult(
                false,
                "Не удалось выделить весь текст: узел в фокусе не отдаёт текст (пусто), поэтому " +
                    "диапазон выделения определить нельзя."
            )
        }
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
            ToolResult(true, "Выделил весь текст (0..${text.length}).")
        } else {
            ToolResult(
                false,
                "Узел в фокусе не поддержал выделение текста (ACTION_SET_SELECTION с диапазоном " +
                    "0..${text.length} отклонён)."
            )
        }
    }

    /**
     * `ui_accessibility_status`: honest, read-only report of whether Меха's accessibility service
     * is enabled in the system and connected to the app. Uses
     * `AccessibilityManager.getEnabledAccessibilityServiceList` (enabled) plus the live service
     * instance (connected); never requires a permission and never throws for a normal state.
     *
     * https://developer.android.com/reference/android/view/accessibility/AccessibilityManager#getEnabledAccessibilityServiceList(int)
     */
    fun accessibilityStatusAction(): ToolResult {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return ToolResult(
                false,
                "Не удалось получить AccessibilityManager: состояние службы спецвозможностей неизвестно."
            )
        val enabled = try {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
        } catch (e: Exception) {
            return ToolResult(false, "Не удалось прочитать список служб спецвозможностей: ${e.message}")
        }
        val connected = AccessibilityAutomationService.isEnabled()
        val state = when {
            connected -> "включена и подключена"
            enabled -> "включена в системе, но не подключена к приложению (возможно, процесс только что перезапущен)"
            else -> "выключена"
        }
        return ToolResult(
            true,
            "Служба спецвозможностей Меха: $state (enabled=$enabled, connected=$connected)."
        )
    }

    /**
     * `ui_accessibility_open_settings`: opens the system Accessibility settings screen so the user
     * can toggle Меха's service. `NEW_TASK` is required because the engine may be invoked from a
     * non-Activity context. Failure to open is reported honestly.
     *
     * https://developer.android.com/reference/android/provider/Settings#ACTION_ACCESSIBILITY_SETTINGS
     */
    fun openAccessibilitySettingsAction(): ToolResult = try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        ToolResult(
            true,
            "Открыл системный экран «Специальные возможности». Включите службу Меха вручную " +
                "(переключатель рядом с приложением) и вернитесь в Меха."
        )
    } catch (e: Exception) {
        ToolResult(false, "Не удалось открыть настройки спецвозможностей: ${e.message}")
    }

    // ---- helpers ----

    /** Compact one-line rendering of a resolved node, including the centre for a follow-up ui_tap. */
    internal fun describeNode(node: UiNode): String {
        val flags = buildString {
            if (node.clickable) append(" [click]")
            if (node.editable) append(" [edit]")
            if (node.scrollable) append(" [scroll]")
        }
        return "text=«${node.text ?: "-"}» desc=«${node.contentDescription ?: "-"}» " +
            "class=${node.className ?: "?"}$flags id=${node.resourceId ?: "-"} " +
            "bounds=(${node.bounds.left},${node.bounds.top})-(${node.bounds.right},${node.bounds.bottom}) " +
            "центр=(${node.bounds.centerX},${node.bounds.centerY})"
    }

    /** Honest "nothing matched" message that lists every criterion the resolver actually tried. */
    internal fun notFound(selector: UiSelector): String =
        "Элемент не найден по селектору (${selector.description}). Сначала снимите ui_tree или уточните " +
            "критерии (text/textContains/contentDescription/resourceId/className/index)."

    private fun labelOf(node: AccessibilityNodeInfo): String =
        (node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.viewIdResourceName?.substringAfterLast('/')
            ?: "элемент").take(60)

    internal fun nodeLabel(node: UiNode): String =
        (node.text?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.takeIf { it.isNotBlank() }
            ?: node.resourceId
            ?: "элемент").take(60)

    /** Maps the pure [KeyEventMapper.GlobalAction] to the platform `GLOBAL_ACTION_*` constant. */
    private fun androidGlobalAction(action: KeyEventMapper.GlobalAction): Int = when (action) {
        KeyEventMapper.GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
        KeyEventMapper.GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
        KeyEventMapper.GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        KeyEventMapper.GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        KeyEventMapper.GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        KeyEventMapper.GlobalAction.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        KeyEventMapper.GlobalAction.SCREENSHOT -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
    }

    /**
     * The currently focused node: input focus first, then accessibility focus. Both constants and
     * the lookup are documented on `AccessibilityNodeInfo.findFocus`/`FOCUS_INPUT`.
     *
     * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#findFocus(int)
     */
    private fun focusedNode(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }

    /**
     * Runs a clipboard-style [action] (`ACTION_COPY`/`ACTION_PASTE`/`ACTION_CUT`) on the focused
     * node. A missing focus or a `false` from `performAction` becomes an honest failure naming the
     * [actionName] — never a fake success.
     *
     * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#performAction(int)
     */
    private fun focusedNodeAction(
        service: AccessibilityService,
        action: Int,
        actionName: String,
    ): ToolResult {
        val node = focusedNode(service)
            ?: return ToolResult(
                false,
                "Нет узла в фокусе: сначала установите фокус на поле или выделите текст. " +
                    "Действие «$actionName» не выполнено."
            )
        val ok = try {
            node.performAction(action)
        } catch (e: Exception) {
            false
        }
        return if (ok) {
            ToolResult(true, "Действие «$actionName» выполнено.")
        } else {
            ToolResult(
                false,
                "Узел в фокусе не поддержал действие «$actionName» (performAction вернул false): " +
                    "нет выделения, буфер пуст или поле не редактируемое."
            )
        }
    }

    /**
     * Builds the [UiSelector] for a tool call, accepting the new selector names (`text`/`textContains`/
     * `contentDescription`/`resourceId`/`className`/`index`) alongside the legacy aliases (`desc`,
     * `id`, and `text` unless the tool's `text` is the payload — see [textIsSelector]). `exact=true`
     * turns a `text` argument into an exact match, otherwise it is a case-insensitive substring match,
     * which is the historic behaviour of `ui_find`/`ui_tap`.
     */
    fun selectorFrom(arguments: JsonObject, textIsSelector: Boolean): UiSelector {
        val exact = arguments.str("exact")?.toBooleanStrictOrNull() ?: false
        val rawText = if (textIsSelector) arguments.str("text") else arguments.str("selectorText")
        return UiSelector(
            text = if (exact) rawText else null,
            textContains = arguments.str("textContains") ?: if (!exact) rawText else null,
            resourceId = arguments.str("resourceId") ?: arguments.str("id"),
            contentDescription = arguments.str("contentDescription") ?: arguments.str("desc"),
            className = arguments.str("className"),
            index = arguments.str("index")?.toIntOrNull()?.takeIf { it >= 1 },
        )
    }

    /** Reads the live tree once and returns the best-ranked match, or `null` (no window / no match). */
    internal fun resolveOnService(service: AccessibilityService, selector: UiSelector): UiMatch? {
        if (selector.isEmpty) return null
        val root = service.rootInActiveWindow ?: return null
        val tree = AccessibilityTree.toUiNode(root) ?: return null
        return UiSelectorResolver.resolve(selector, tree)
    }

    /** Reads the live tree once and returns every ranked match; `null` means "no active window". */
    internal fun resolveAllOnService(service: AccessibilityService, selector: UiSelector): List<UiMatch>? {
        val root = service.rootInActiveWindow ?: return null
        val tree = AccessibilityTree.toUiNode(root) ?: return null
        return UiSelectorResolver.resolveAll(selector, tree)
    }

    internal fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }

    internal fun tapStroke(x: Float, y: Float): GestureDescription = stroke(x, y, x, y, 50)

    internal fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): GestureDescription {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    internal suspend fun gestureResult(
        service: AccessibilityService,
        gesture: GestureDescription,
        successText: String
    ): ToolResult = withContext(Dispatchers.Main) {
        val ok = service.dispatchGesture(gesture, null, null)
        if (ok) ToolResult(true, successText) else ToolResult(false, "Не удалось выполнить жест.")
    }

    /**
     * Dispatches [gesture] and suspends until the accessibility framework reports completion.
     *
     * A bounded timeout wraps the wait so a missing callback can never hang the agent tool call. When
     * `dispatchGesture` returns false the gesture was rejected outright (service not connected, or
     * `canPerformGestures` not granted) and that is reported as a failure immediately.
     *
     * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler)
     */
    internal suspend fun dispatchAndAwait(
        service: AccessibilityService,
        gesture: GestureDescription,
        durationMs: Long
    ): Boolean = withTimeoutOrNull(durationMs + CALLBACK_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val accepted = try {
                service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    handler
                )
            } catch (e: Exception) {
                false
            }
            if (!accepted && cont.isActive) cont.resume(false)
        }
    } ?: false

    internal companion object {
        /** Extra budget on top of the gesture duration while waiting for the completion callback. */
        private const val CALLBACK_TIMEOUT_MS = 1_500L

        /** `ui_find`: default and hard cap on returned matches. */
        const val DEFAULT_FIND_LIMIT = 20
        const val MAX_FIND_RESULTS = 100

        /** `ui_wait_for_text`: bounded poll budget and interval (hard caps prevent a stuck turn). */
        const val DEFAULT_WAIT_MS = 8_000L
        const val MAX_WAIT_MS = 30_000L
        const val MIN_WAIT_MS = 100L
        const val DEFAULT_POLL_MS = 400L
        const val MIN_POLL_MS = 50L
        const val MAX_POLL_MS = 5_000L

        /** `ui_scroll_until`: bounded swipe budget and settle time between swipes. */
        const val DEFAULT_SCROLL_UNTIL_SWIPES = 8
        const val MAX_SCROLL_UNTIL_SWIPES = 30
        const val SCROLL_UNTIL_SETTLE_MS = 400L
    }
}
