package com.andharness.ru.data.tools.ui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.ui.GestureBounds
import com.andharness.ru.domain.ui.UiSelectorResolver
import kotlinx.serialization.json.JsonObject

/**
 * The gesture/tap action family of [UiActionEngine]: `ui_tap`, `ui_long_press`, `ui_swipe`,
 * `ui_scroll` and `ui_gesture_multi`. Split out of `UiActionEngine.kt` as extension functions;
 * the shared stroke/gesture helpers stay on the engine. Bodies and behaviour are unchanged.
 */

internal suspend fun UiActionEngine.tapAction(service: AccessibilityService, arguments: JsonObject): ToolResult {
    val x = arguments.str("x")?.toIntOrNull()
    val y = arguments.str("y")?.toIntOrNull()
    if (x != null && y != null) {
        return gestureResult(service, tapStroke(x.toFloat(), y.toFloat()), "Тап ($x,$y)")
    }
    val selector = selectorFrom(arguments, textIsSelector = true)
    if (selector.isEmpty) {
        return ToolResult(
            false,
            "Укажите координаты (x, y) или selector (text, contentDescription, resourceId, " +
                "className, index)."
        )
    }
    val match = resolveOnService(service, selector)
        ?: return ToolResult(false, notFound(selector))
    // Prefer a clickable target: the matched label itself, or its nearest clickable ancestor.
    val target = UiSelectorResolver.bestClickable(match) ?: match.node
    val real = target.ref as? AccessibilityNodeInfo
    if (real != null && real.isClickable && real.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        return ToolResult(true, "Нажал: ${nodeLabel(target)}")
    }
    return gestureResult(
        service,
        tapStroke(target.bounds.centerX.toFloat(), target.bounds.centerY.toFloat()),
        "Нажал: ${nodeLabel(target)}"
    )
}

internal suspend fun UiActionEngine.longPressAction(service: AccessibilityService, arguments: JsonObject): ToolResult {
    val x = arguments.str("x")?.toIntOrNull()
    val y = arguments.str("y")?.toIntOrNull()
    if (x != null && y != null) {
        return gestureResult(
            service,
            stroke(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 700),
            "Долгое нажатие ($x,$y)"
        )
    }
    val selector = selectorFrom(arguments, textIsSelector = true)
    if (selector.isEmpty) {
        return ToolResult(
            false,
            "Укажите координаты (x, y) или selector (text, contentDescription, resourceId, " +
                "className, index)."
        )
    }
    val match = resolveOnService(service, selector)
        ?: return ToolResult(false, notFound(selector))
    val target = UiSelectorResolver.bestClickable(match) ?: match.node
    val real = target.ref as? AccessibilityNodeInfo
    if (real != null && real.isLongClickable &&
        real.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    ) {
        return ToolResult(true, "Долгое нажатие: ${nodeLabel(target)}")
    }
    return gestureResult(
        service,
        stroke(
            target.bounds.centerX.toFloat(),
            target.bounds.centerY.toFloat(),
            target.bounds.centerX.toFloat(),
            target.bounds.centerY.toFloat(),
            700,
        ),
        "Долгое нажатие: ${nodeLabel(target)}"
    )
}

internal suspend fun UiActionEngine.swipeAction(service: AccessibilityService, arguments: JsonObject): ToolResult {
    val x1 = arguments.str("x1")?.toIntOrNull()
    val y1 = arguments.str("y1")?.toIntOrNull()
    val x2 = arguments.str("x2")?.toIntOrNull()
    val y2 = arguments.str("y2")?.toIntOrNull()
    if (x1 == null || y1 == null || x2 == null || y2 == null) {
        return ToolResult(false, "Укажите x1, y1, x2, y2.")
    }
    val duration = arguments.str("duration_ms")?.toLongOrNull()?.coerceIn(50, 3000) ?: 300
    return gestureResult(
        service,
        stroke(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration),
        "Свайп"
    )
}

internal suspend fun UiActionEngine.scrollAction(service: AccessibilityService, arguments: JsonObject): ToolResult {
    val direction = (arguments.str("direction") ?: "down").lowercase()
    val forward = direction == "down" || direction == "forward" || direction == "вниз"
    val scrollable = findScrollable(service.rootInActiveWindow)
    if (scrollable != null) {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (scrollable.performAction(action)) {
            return ToolResult(true, "Прокрутил: $direction")
        }
    }
    // Fallback: swipe in the middle of the screen.
    val dm = context.resources.displayMetrics
    val cx = dm.widthPixels / 2f
    val fromY = if (forward) dm.heightPixels * 0.7f else dm.heightPixels * 0.3f
    val toY = if (forward) dm.heightPixels * 0.3f else dm.heightPixels * 0.7f
    return gestureResult(service, stroke(cx, fromY, cx, toY, 300), "Прокрутил: $direction")
}

internal suspend fun UiActionEngine.gestureMultiAction(
    service: AccessibilityService,
    arguments: JsonObject,
): ToolResult {
    val cx = arguments.str("cx")?.toFloatOrNull()
    val cy = arguments.str("cy")?.toFloatOrNull()
    val startDistance = arguments.str("start_distance")?.toFloatOrNull()
    val endDistance = arguments.str("end_distance")?.toFloatOrNull()
    if (cx == null || cy == null || startDistance == null || endDistance == null) {
        return ToolResult(false, "Укажите cx, cy, start_distance и end_distance (числа в пикселях).")
    }
    if (startDistance < 0f || endDistance < 0f) {
        return ToolResult(false, "start_distance и end_distance не могут быть отрицательными.")
    }
    if (startDistance == 0f && endDistance == 0f) {
        return ToolResult(false, "start_distance и end_distance не могут быть одновременно нулевыми.")
    }
    val duration = arguments.str("duration_ms")?.toLongOrNull()?.coerceIn(50, 3_000) ?: 400L

    // Two simultaneous strokes moved symmetrically around (cx, cy) make a pinch / zoom gesture:
    // both pointers travel from start_distance to end_distance apart. The requested points can
    // fall outside the display (cx=360 with end_distance=800 puts the left finger at x=-40), and
    // GestureDescription.StrokeDescription rejects a path with negative bounds with a raw
    // "Path bounds must not be negative" (BUG-024). Clamp every coordinate to the screen first;
    // the pure math lives in GestureBounds and is covered by a JVM test.
    // https://developer.android.com/reference/android/accessibilityservice/GestureDescription.StrokeDescription
    val display = context.resources.displayMetrics
    val bounds = GestureBounds.clamp(
        cx = cx,
        cy = cy,
        startDistance = startDistance,
        endDistance = endDistance,
        width = display.widthPixels,
        height = display.heightPixels,
    ) ?: return ToolResult(
        false,
        "Не удалось определить размер экрана — мультитач-жест не выполнен."
    )
    if (bounds.startSpan == 0 && bounds.endSpan == 0) {
        return ToolResult(
            false,
            "Жест вырожденный: обе точки совпадают (cx/cy вне экрана или нулевая дистанция). " +
                "Укажите cx и cy в пределах экрана и ненулевой end_distance."
        )
    }
    val left = Path().apply {
        moveTo(bounds.left.startX.toFloat(), bounds.left.y.toFloat())
        lineTo(bounds.left.endX.toFloat(), bounds.left.y.toFloat())
    }
    val right = Path().apply {
        moveTo(bounds.right.startX.toFloat(), bounds.right.y.toFloat())
        lineTo(bounds.right.endX.toFloat(), bounds.right.y.toFloat())
    }
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(left, 0, duration))
        .addStroke(GestureDescription.StrokeDescription(right, 0, duration))
        .build()

    val completed = dispatchAndAwait(service, gesture, duration)
    return if (completed) {
        val clipping = if (bounds.exact) "" else
            " (жест ужат до границ экрана: ${bounds.startSpan} → ${bounds.endSpan} px)"
        ToolResult(
            true,
            "Мультитач-жест выполнен: центр ($cx, $cy), $startDistance → $endDistance px " +
                "за ${duration}мс$clipping."
        )
    } else {
        ToolResult(
            false,
            "Система не выполнила мультитач-жест. Проверьте, что доступ спецвозможностей включён " +
                "(и canPerformGestures=true), и что жест не перехвачен."
        )
    }
}
