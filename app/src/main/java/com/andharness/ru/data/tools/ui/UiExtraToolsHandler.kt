package com.andharness.ru.data.tools.ui

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The extra `ui_*` tools layered on top of the original accessibility set:
 * `ui_copy`, `ui_paste`, `ui_cut`, `ui_select_all`, `ui_accessibility_status` and
 * `ui_accessibility_open_settings`.
 *
 * All action bodies live in [UiActionEngine] (consistent with `key_event`); these classes are the
 * thin registry glue. Unlike `ui_tap`/`ui_type`, they deliberately do NOT call
 * `UiActionEngine.recordIfActive`: recording a step the macro replayer ([UiMacroReplay.runStep])
 * cannot replay would corrupt a recorded macro, so these stay non-recordable.
 *
 * Official references:
 * - `AccessibilityNodeInfo.ACTION_COPY` / `ACTION_PASTE` / `ACTION_CUT` (API 18):
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#ACTION_COPY
 * - `AccessibilityNodeInfo.ACTION_SET_SELECTION` (API 18):
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#ACTION_SET_SELECTION
 * - `AccessibilityManager.getEnabledAccessibilityServiceList`:
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityManager#getEnabledAccessibilityServiceList(int)
 * - `Settings.ACTION_ACCESSIBILITY_SETTINGS`:
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_ACCESSIBILITY_SETTINGS
 */

/** `ui_copy`: copies the focused node's current selection to the clipboard. */
internal class UiCopyToolHandler(
    private val engine: UiActionEngine,
) : ToolHandler {
    override val name = "ui_copy"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val service = engine.serviceOrNull() ?: return@withContext engine.notEnabled()
        try {
            engine.copyAction(service)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}

/** `ui_paste`: pastes the clipboard into the focused node. */
internal class UiPasteToolHandler(
    private val engine: UiActionEngine,
) : ToolHandler {
    override val name = "ui_paste"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val service = engine.serviceOrNull() ?: return@withContext engine.notEnabled()
        try {
            engine.pasteAction(service)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}

/** `ui_cut`: cuts the focused node's current selection to the clipboard. */
internal class UiCutToolHandler(
    private val engine: UiActionEngine,
) : ToolHandler {
    override val name = "ui_cut"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val service = engine.serviceOrNull() ?: return@withContext engine.notEnabled()
        try {
            engine.cutAction(service)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}

/** `ui_select_all`: selects all text in the focused editable node. */
internal class UiSelectAllToolHandler(
    private val engine: UiActionEngine,
) : ToolHandler {
    override val name = "ui_select_all"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val service = engine.serviceOrNull() ?: return@withContext engine.notEnabled()
        try {
            engine.selectAllAction(service)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}

/** `ui_accessibility_status`: read-only report of the accessibility service's enabled/connected state. */
internal class UiAccessibilityStatusToolHandler(
    private val engine: UiActionEngine,
) : ToolHandler {
    override val name = "ui_accessibility_status"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        try {
            engine.accessibilityStatusAction()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}

/** `ui_accessibility_open_settings`: opens the system accessibility settings screen. */
internal class UiAccessibilityOpenSettingsToolHandler(
    private val engine: UiActionEngine,
) : ToolHandler {
    override val name = "ui_accessibility_open_settings"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        try {
            engine.openAccessibilitySettingsAction()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
