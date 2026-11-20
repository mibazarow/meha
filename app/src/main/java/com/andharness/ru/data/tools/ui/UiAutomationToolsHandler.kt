package com.andharness.ru.data.tools.ui

import android.content.Context
import com.andharness.ru.data.screenmap.ScreenMapStore
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonAliasRepository
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.andharness.ru.domain.serialization.AppJson

/**
 * Builds one [ToolHandler] per UI-automation tool powered by the
 * [com.andharness.ru.service.AccessibilityAutomationService]: [ui_tree], [ui_tap], [ui_long_press],
 * [ui_swipe], [ui_scroll], [ui_find], [ui_wait_for_text], [ui_scroll_until], [ui_type], [key_event],
 * [ui_copy], [ui_paste], [ui_cut], [ui_select_all], [ui_accessibility_status],
 * [ui_accessibility_open_settings],
 * [screen_capture], [ui_gesture_multi], [ui_trigger_subscribe], [ui_macro_start], [ui_macro_stop],
 * [ui_macro_list], [ui_macro_run], [ui_macro_delete] — in their original registration order.
 *
 * [remember_screen] also lives here because it reads the same accessibility tree; it additionally
 * needs the memory/person repositories to save the current screen as a memory note.
 *
 * References (official Android docs):
 * - `AccessibilityNodeInfo` (text, contentDescription, viewIdResourceName, isClickable, actions):
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
 * - `AccessibilityNodeInfo.performAction` / ACTION_CLICK / ACTION_LONG_CLICK / ACTION_SET_TEXT /
 *   ACTION_SCROLL_FORWARD / ACTION_SCROLL_BACKWARD:
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#performAction(int)
 * - `AccessibilityService.performGlobalAction` (GLOBAL_ACTION_BACK/HOME/RECENTS/...):
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#performGlobalAction(int)
 * - `dispatchGesture` + `GestureDescription` / `GestureDescription.StrokeDescription`:
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler)
 */
class UiAutomationToolsHandler(
    private val context: Context,
    /**
     * Optional per-app screen map (checklist §1 «Знание экранов»). When present, every successful
     * `ui_tree`/`ui_find`/`ui_tap`/`ui_type`/`ui_scroll` best-effort records the current screen so the
     * agent does not have to re-scan a familiar app from scratch. `null` (e.g. in tests) keeps the
     * behaviour exactly as before: recording is a cache side-effect and never affects the tool result.
     */
    private val screenMapStore: ScreenMapStore? = null,
    /** Memory sink for [remember_screen]. */
    private val assistRepository: AssistRepository,
    /** Person repositories used to resolve the optional `person` argument of [remember_screen]. */
    private val personRepository: PersonRepository,
    private val personIdentityRepository: PersonIdentityRepository,
    private val personAliasRepository: PersonAliasRepository,
    private val interactionRepository: InteractionRepository,
) {

    /** Off-main scope for the best-effort screen-map writes; a slow disk never delays a tool call. */
    private val screenMapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Persisted UI macros (SharedPreferences + JSON), mirroring `GeofenceStore`. */
    private val macros = UiMacroStore(context)

    /** In-progress recording of tap/swipe/type/... actions, fed by the action handlers below. */
    private val recorder = UiMacroRecorder()

    /** Parser for the stored per-step `argsJson` during `ui_macro_run` replay. */
    private val stepParser = AppJson.Lenient

    fun handlers(): List<ToolHandler> {
        val screenRecorder = UiScreenRecorder(screenMapStore, screenMapScope)
        val engine = UiActionEngine(context, recorder, screenRecorder)
        val capture = UiScreenCapture(context)
        val replay = UiMacroReplay(engine, stepParser)
        return listOf(
            UiTreeToolHandler(engine),
            UiTapToolHandler(engine),
            UiLongPressToolHandler(engine),
            UiSwipeToolHandler(engine),
            UiScrollToolHandler(engine),
            UiFindToolHandler(engine),
            UiWaitForTextToolHandler(engine),
            UiScrollUntilToolHandler(engine),
            UiTypeToolHandler(engine),
            KeyEventToolHandler(engine),
            UiCopyToolHandler(engine),
            UiPasteToolHandler(engine),
            UiCutToolHandler(engine),
            UiSelectAllToolHandler(engine),
            UiAccessibilityStatusToolHandler(engine),
            UiAccessibilityOpenSettingsToolHandler(engine),
            ScreenCaptureToolHandler(engine, capture),
            UiGestureMultiToolHandler(engine),
            UiTriggerSubscribeToolHandler(),
            UiMacroStartToolHandler(recorder),
            UiMacroStopToolHandler(recorder, macros),
            UiMacroListToolHandler(macros),
            UiMacroRunToolHandler(engine, macros, replay),
            UiMacroDeleteToolHandler(macros),
            RememberScreenToolHandler(
                context,
                assistRepository,
                personRepository,
                personIdentityRepository,
                personAliasRepository,
                interactionRepository,
            ),
        )
    }
}
