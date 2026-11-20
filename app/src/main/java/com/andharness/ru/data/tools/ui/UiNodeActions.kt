package com.andharness.ru.data.tools.ui

import android.accessibilityservice.AccessibilityService
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The node-inspection action family of [UiActionEngine]: `ui_find`, `ui_wait_for_text` and
 * `ui_scroll_until`. Split out of `UiActionEngine.kt` as extension functions; the selector/tree
 * resolution and node-describing helpers stay on the engine. Bodies and behaviour are unchanged.
 */

/**
 * `ui_find`: resolves the [com.andharness.ru.domain.ui.UiSelector] built from the arguments against
 * the *full* accessibility hierarchy and returns the ranked matches (score, label, class, flags,
 * screen bounds and centre), so a following `ui_tap`/`ui_type` can act on them. Bounded by the
 * requested `limit`.
 *
 * Accepts the new selector names (`text`/`textContains`/`contentDescription`/`resourceId`/
 * `className`/`index`) and the historic ones (`text`/`contentDescription`/`resourceId`/`exact`).
 *
 * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
 * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getBoundsInScreen(android.graphics.Rect)
 */
internal fun UiActionEngine.findAction(service: AccessibilityService, arguments: JsonObject): ToolResult {
    val selector = selectorFrom(arguments, textIsSelector = true)
    if (selector.isEmpty) {
        return ToolResult(
            false,
            "Укажите хотя бы один критерий: text, textContains, contentDescription, resourceId, " +
                "className или index."
        )
    }
    val limit = arguments.str("limit")?.toIntOrNull()?.coerceIn(1, UiActionEngine.MAX_FIND_RESULTS)
        ?: UiActionEngine.DEFAULT_FIND_LIMIT
    val matches = resolveAllOnService(service, selector)
        ?: return ToolResult(false, "Нет активного окна.")
    if (matches.isEmpty()) {
        return ToolResult(true, "Ничего не найдено по критериям (${selector.description}).")
    }
    val shown = matches.take(limit)
    return ToolResult(
        true,
        buildString {
            appendLine("Найдено элементов: ${matches.size} (показаны первые ${shown.size}).")
            shown.forEachIndexed { index, match ->
                append(index + 1).append(") [score=").append(match.score).append("] ")
                    .append(describeNode(match.node)).appendLine()
            }
        }.trim(),
    )
}

/**
 * `ui_wait_for_text`: polls the accessibility tree on a background dispatcher until a node that
 * satisfies the [com.andharness.ru.domain.ui.UiSelector] appears, or the bounded timeout elapses.
 * `delay` suspends, so the UI thread is never blocked; the elapsed time and the found node's bounds
 * are reported.
 *
 * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow()
 */
internal suspend fun UiActionEngine.waitForTextAction(
    service: AccessibilityService,
    arguments: JsonObject,
): ToolResult = withContext(Dispatchers.Default) {
    val selector = selectorFrom(arguments, textIsSelector = true)
    if (selector.isEmpty) {
        return@withContext ToolResult(
            false,
            "Укажите text или другой критерий (contentDescription, resourceId, className)."
        )
    }
    val timeoutMs = arguments.str("timeoutMs")?.toLongOrNull()?.coerceIn(UiActionEngine.MIN_WAIT_MS, UiActionEngine.MAX_WAIT_MS)
        ?: UiActionEngine.DEFAULT_WAIT_MS
    val intervalMs = arguments.str("intervalMs")?.toLongOrNull()?.coerceIn(UiActionEngine.MIN_POLL_MS, UiActionEngine.MAX_POLL_MS)
        ?: UiActionEngine.DEFAULT_POLL_MS
    val started = System.currentTimeMillis()
    val deadline = started + timeoutMs

    while (true) {
        if (serviceOrNull() == null) {
            return@withContext ToolResult(
                false,
                "Служба спецвозможностей отключилась во время ожидания (${selector.description})."
            )
        }
        val found = resolveOnService(service, selector)
        if (found != null) {
            val elapsed = System.currentTimeMillis() - started
            return@withContext ToolResult(
                true,
                "Элемент (${selector.description}) появился через $elapsed мс. ${describeNode(found.node)}"
            )
        }
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) break
        delay(minOf(intervalMs, remaining))
    }
    ToolResult(false, "Не дождался элемента (${selector.description}) за $timeoutMs мс.")
}

/**
 * `ui_scroll_until`: checks the current screen first, then repeatedly applies the exact gesture
 * `ui_scroll` uses (scrollable node action, else a mid-screen swipe) and re-reads the tree after a
 * bounded settle, stopping as soon as the [com.andharness.ru.domain.ui.UiSelector] target appears
 * or the swipe budget runs out.
 *
 * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#performAction(int)
 */
internal suspend fun UiActionEngine.scrollUntilAction(
    service: AccessibilityService,
    arguments: JsonObject,
): ToolResult {
    val selector = selectorFrom(arguments, textIsSelector = true)
    if (selector.isEmpty) {
        return ToolResult(
            false,
            "Укажите text или другой критерий (contentDescription, resourceId, className)."
        )
    }
    val direction = (arguments.str("direction") ?: "down").lowercase()
    val maxSwipes = arguments.str("maxSwipes")?.toIntOrNull()
        ?.coerceIn(1, UiActionEngine.MAX_SCROLL_UNTIL_SWIPES) ?: UiActionEngine.DEFAULT_SCROLL_UNTIL_SWIPES

    resolveOnService(service, selector)?.let {
        return ToolResult(true, "Элемент (${selector.description}) уже на экране. ${describeNode(it.node)}")
    }
    var swipes = 0
    while (swipes < maxSwipes) {
        val scroll = scrollAction(service, arguments)
        if (!scroll.success) {
            return ToolResult(
                false,
                "Не удалось прокрутить экран на шаге ${swipes + 1}: ${scroll.output}"
            )
        }
        swipes++
        delay(UiActionEngine.SCROLL_UNTIL_SETTLE_MS)
        val found = resolveOnService(service, selector)
        if (found != null) {
            return ToolResult(
                true,
                "Элемент (${selector.description}) найден после $swipes свайп(ов) ($direction). " +
                    describeNode(found.node)
            )
        }
    }
    return ToolResult(false, "Не найдено (${selector.description}) после $maxSwipes свайпов ($direction).")
}
