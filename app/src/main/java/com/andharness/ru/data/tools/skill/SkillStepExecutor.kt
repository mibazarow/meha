package com.andharness.ru.data.tools.skill

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.andharness.ru.data.tools.ui.AccessibilityTree
import com.andharness.ru.domain.skill.SkillScrollDirection
import com.andharness.ru.domain.ui.UiMatch
import com.andharness.ru.domain.ui.UiNode
import com.andharness.ru.domain.ui.UiSelector
import com.andharness.ru.domain.ui.UiSelectorResolver
import com.andharness.ru.service.AccessibilityAutomationService
import kotlinx.coroutines.delay

/**
 * Executes one [PlannedStep] on the live accessibility tree ([AccessibilityTree.toUiNode] +
 * [UiSelectorResolver]) and the [AccessibilityAutomationService], exactly like the `ui_*` tools do,
 * so a replayed skill step behaves like a fresh tool call.
 *
 * Robustness rules (SPEC §24.2): `tap`/`wait_for_text` retry while the node appears late; if the
 * service is OFF when an accessibility step is reached it reports a clear Russian error. The caller
 * ([SkillRunEngine]) owns the per-step timeout and the abort-on-failure policy.
 *
 * References (official Android docs):
 * - `AccessibilityService` / `getRootInActiveWindow`:
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow()
 * - `AccessibilityNodeInfo.performAction` (ACTION_CLICK / ACTION_SET_TEXT / ACTION_SCROLL_*):
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#performAction(int)
 * - `AccessibilityService.performGlobalAction` (GLOBAL_ACTION_BACK/HOME/...):
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#performGlobalAction(int)
 * - `AccessibilityService.dispatchGesture` + `GestureDescription`:
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler)
 * - `PackageManager.getLaunchIntentForPackage` (app launching):
 *   https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String)
 * - `Intent.ACTION_VIEW` + `startActivity` (external browser):
 *   https://developer.android.com/reference/android/content/Intent#ACTION_VIEW
 */
internal class SkillStepExecutor(private val context: Context) {

    suspend fun execute(step: PlannedStep): StepOutcome = when (step.action) {
        SkillActions.LAUNCH_APP -> launchApp(step)
        SkillActions.OPEN_URL -> openUrl(step)
        SkillActions.DELAY -> {
            delay(step.ms.coerceIn(1L, MAX_DELAY_MS))
            StepOutcome(true, "пауза ${step.ms} мс")
        }
        SkillActions.TAP -> tap(step)
        SkillActions.TYPE -> type(step)
        SkillActions.WAIT_FOR_TEXT -> waitForText(step)
        SkillActions.SCROLL -> scroll(step)
        SkillActions.KEY -> key(step)
        SkillActions.ASSERT_TEXT -> assertText(step)
        else -> StepOutcome(false, "неизвестное действие «${step.action}»")
    }

    private fun launchApp(step: PlannedStep): StepOutcome {
        val pkg = step.packageName
            ?: return StepOutcome(false, "не задан packageName")
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return StepOutcome(false, "приложение «$pkg» не установлено (нет точки запуска)")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            StepOutcome(true, "запущено «$pkg»")
        } catch (e: Exception) {
            StepOutcome(false, "не удалось запустить «$pkg»: ${e.message}")
        }
    }

    private fun openUrl(step: PlannedStep): StepOutcome {
        val url = step.payload ?: return StepOutcome(false, "не задан URL")
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            StepOutcome(true, "открыта ссылка $url")
        } catch (e: Exception) {
            StepOutcome(false, "не удалось открыть ссылку $url: ${e.message}")
        }
    }

    private suspend fun tap(step: PlannedStep): StepOutcome {
        val selector = step.selector ?: return StepOutcome(false, "не задан селектор")
        val service = service() ?: return serviceGone()
        val match = resolveWithRetry(service, selector)
            ?: return StepOutcome(false, notFound(selector))
        val target = UiSelectorResolver.bestClickable(match) ?: match.node
        val real = target.ref as? AccessibilityNodeInfo
        if (real != null && real.isClickable &&
            real.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            return StepOutcome(true, "нажато: ${nodeLabel(target)}")
        }
        val dispatched = service.dispatchGesture(tapStroke(target), null, null)
        return if (dispatched) StepOutcome(true, "нажато: ${nodeLabel(target)}")
        else StepOutcome(false, "жест нажатия отклонён системой")
    }

    private suspend fun type(step: PlannedStep): StepOutcome {
        val selector = step.selector
        val text = step.payload ?: return StepOutcome(false, "не задан текст")
        val service = service() ?: return serviceGone()
        val match: UiMatch? = if (selector != null) {
            resolveWithRetry(service, selector) ?: return StepOutcome(false, notFound(selector))
        } else {
            null
        }
        val target = (match?.node?.ref as? AccessibilityNodeInfo)
            ?: (match?.let { UiSelectorResolver.bestClickable(it) }?.ref as? AccessibilityNodeInfo)
            ?: service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return StepOutcome(false, "поле ввода не найдено (нет фокуса и селектор не задан)")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            StepOutcome(true, "введено «$text» в «${labelOf(target)}»")
        } else {
            StepOutcome(false, "не удалось ввести текст (элемент не редактируемый)")
        }
    }

    private suspend fun waitForText(step: PlannedStep): StepOutcome {
        val selector = step.selector ?: return StepOutcome(false, "не задан селектор")
        val timeout = step.timeoutMs.coerceIn(1L, MAX_WAIT_MS)
        val started = System.currentTimeMillis()
        val deadline = started + timeout
        while (true) {
            val service = service() ?: return serviceGone()
            val match = resolveWithRetry(service, selector, attempts = 1)
            if (match != null) {
                val elapsed = System.currentTimeMillis() - started
                return StepOutcome(true, "элемент появился через $elapsed мс (${nodeLabel(match.node)})")
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(minOf(POLL_MS, remaining))
        }
        return StepOutcome(false, "не дождался элемента (${selector.description}) за $timeout мс")
    }

    private suspend fun scroll(step: PlannedStep): StepOutcome {
        val direction = step.direction
        val times = step.amount.coerceIn(1, MAX_SCROLL_STEPS)
        var done = 0
        repeat(times) {
            val service = service() ?: return serviceGone()
            val forward = direction == SkillScrollDirection.DOWN.name ||
                direction == SkillScrollDirection.RIGHT.name
            val horizontal = direction == SkillScrollDirection.LEFT.name ||
                direction == SkillScrollDirection.RIGHT.name
            val scrollable = if (horizontal) null else findScrollable(service.rootInActiveWindow)
            val ok = when {
                scrollable != null -> scrollable.performAction(
                    if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )
                else -> service.dispatchGesture(swipeStroke(forward, horizontal), null, null)
            }
            if (!ok) {
                return StepOutcome(false, "прокрутка (${direction ?: "?"}) не выполнена на попытке ${done + 1}")
            }
            done++
            delay(SCROLL_SETTLE_MS)
        }
        return StepOutcome(true, "прокручено $direction x$done")
    }

    private fun key(step: PlannedStep): StepOutcome {
        val service = service() ?: return serviceGone()
        val action = when (step.keyName?.lowercase()) {
            "back", "назад" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home", "домой" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "недавние" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "шторка" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return StepOutcome(
                false,
                "клавиша «${step.keyName}» не поддерживается (back | home | recents | notifications | quick_settings)"
            )
        }
        return if (service.performGlobalAction(action)) StepOutcome(true, "клавиша ${step.keyName}")
        else StepOutcome(false, "система отклонила клавишу ${step.keyName}")
    }

    private suspend fun assertText(step: PlannedStep): StepOutcome {
        val selector = step.selector ?: return StepOutcome(false, "не задан селектор")
        val expected = step.payload ?: return StepOutcome(false, "не задан ожидаемый текст")
        val service = service() ?: return serviceGone()
        val match = resolveWithRetry(service, selector)
            ?: return StepOutcome(false, "элемент не найден (${selector.description})")
        val actual = match.node.text ?: match.node.contentDescription.orEmpty()
        return if (actual.contains(expected, ignoreCase = true)) {
            StepOutcome(true, "подтверждено: «${actual.take(60)}» содержит «$expected»")
        } else {
            StepOutcome(false, "ожидалось «$expected», а на экране «${actual.take(60)}»")
        }
    }

    // ---- helpers ----

    private fun service(): AccessibilityAutomationService? = AccessibilityAutomationService.instance

    private fun serviceGone(): StepOutcome =
        StepOutcome(false, "служба спецвозможностей выключилась во время выполнения")

    private fun notFound(selector: UiSelector): String =
        "элемент не найден по селектору (${selector.description})"

    /** Reads the live tree once, retrying briefly so a node that appears late is still found. */
    private suspend fun resolveWithRetry(
        service: AccessibilityService,
        selector: UiSelector,
        attempts: Int = RESOLVE_ATTEMPTS,
    ): UiMatch? {
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS)
            val tree = service.rootInActiveWindow?.let { AccessibilityTree.toUiNode(it) }
            if (tree != null) {
                UiSelectorResolver.resolve(selector, tree)?.let { return it }
            }
        }
        return null
    }

    private fun nodeLabel(node: UiNode): String =
        (node.text?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.takeIf { it.isNotBlank() }
            ?: node.resourceId
            ?: "элемент").take(60)

    private fun labelOf(node: AccessibilityNodeInfo): String =
        (node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.viewIdResourceName?.substringAfterLast('/')
            ?: "элемент").take(60)

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i))?.let { return it }
        return null
    }

    private fun tapStroke(node: UiNode): GestureDescription = stroke(
        node.bounds.centerX.toFloat(),
        node.bounds.centerY.toFloat(),
        node.bounds.centerX.toFloat(),
        node.bounds.centerY.toFloat(),
        TAP_MS,
    )

    private fun swipeStroke(forward: Boolean, horizontal: Boolean): GestureDescription {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        return if (horizontal) {
            val fromX = if (forward) w * 0.8f else w * 0.2f
            val toX = if (forward) w * 0.2f else w * 0.8f
            stroke(fromX, h / 2f, toX, h / 2f, SWIPE_MS)
        } else {
            val fromY = if (forward) h * 0.7f else h * 0.3f
            val toY = if (forward) h * 0.3f else h * 0.7f
            stroke(w / 2f, fromY, w / 2f, toY, SWIPE_MS)
        }
    }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): GestureDescription {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private companion object {
        /** Small retry for a node that appears late (tap/assert). */
        const val RESOLVE_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 300L

        const val TAP_MS = 50L
        const val SWIPE_MS = 300L
        const val SCROLL_SETTLE_MS = 300L
        const val POLL_MS = 400L

        const val MAX_WAIT_MS = 30_000L
        const val MAX_DELAY_MS = 60_000L
        const val MAX_SCROLL_STEPS = 20
    }
}
