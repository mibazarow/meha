package com.andharness.ru.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Индикация без hover-эффекта: слой состояния рисуется **только** при нажатии
 * ([PressInteraction.Press]) и полностью игнорирует [androidx.compose.foundation.interaction.HoverInteraction].
 *
 * Зачем: на тач-экране наведения нет, но Android при подключённой мыши (например, scrcpy)
 * генерирует hover-взаимодействия, из-за чего многие блоки меняли фон при движении указателя.
 * Эта индикация приходит на смену стандартной и не даёт им подсвечиваться, сохраняя отклик на тап.
 *
 * Реализована по официальному образцу пользовательской индикации на современном API
 * [IndicationNodeFactory] + `DrawModifierNode` (устаревший интерфейс [androidx.compose.foundation.Indication]
 * не используется):
 * - пользовательская индикация — https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures#indication
 * - `IndicationNodeFactory` — https://developer.android.com/reference/kotlin/androidx/compose/foundation/IndicationNodeFactory
 * - `LocalIndication` — https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary
 * - `PressInteraction` / `HoverInteraction` — https://developer.android.com/reference/kotlin/androidx/compose/foundation/interaction/package-summary
 *
 * Слой нажатия рисуется **скруглённым прямоугольником** с небольшим внутренним отступом от
 * границ узла — так отклик читается как мягкая «карточка», а не как резкая полоса во всю ширину:
 * - `DrawScope.drawRoundRect` — https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/drawscope/DrawScope#drawRoundRect
 * - `CornerRadius` — https://developer.android.com/reference/kotlin/androidx/compose/ui/geometry/CornerRadius
 *
 * Версия Compose — BOM 2024.10.01 (foundation 1.7.5, ui-graphics 1.7.5). Точные сигнатуры сверены
 * с артефактами `ui-graphics-android:1.7.5` / `ui-geometry-android:1.7.5`, т.к. сайт документации
 * был недоступен из окружения.
 */
object NoHoverIndication : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        NoHoverIndicationNode(interactionSource)

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun equals(other: Any?): Boolean = other === this
}

/**
 * Узел индикации: слушает [InteractionSource] и рисует полупрозрачный слой поверх контента,
 * пока узел нажат. Любые взаимодействия, кроме нажатия/отпускания/отмены, игнорируются —
 * в частности, hover ничего не рисует.
 */
private class NoHoverIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    /** Прогресс слоя нажатия: 0 — невидим, 1 — проявлен полностью. */
    private val pressProgress = Animatable(RELEASED_PROGRESS)

    /** Текущая анимация проявления/затухания; новая отменяет предыдущую. */
    private var fadeJob: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> animatePressProgress(PRESSED_PROGRESS)
                    is PressInteraction.Release -> animatePressProgress(RELEASED_PROGRESS)
                    is PressInteraction.Cancel -> animatePressProgress(RELEASED_PROGRESS)
                    // HoverInteraction.Enter / HoverInteraction.Exit попадают сюда и намеренно
                    // ничего не делают: hover-слой не появляется.
                    else -> Unit
                }
            }
        }
    }

    /** Короткий фейд к целевому прогрессу; предыдущая анимация отменяется. */
    private fun animatePressProgress(target: Float) {
        fadeJob?.cancel()
        fadeJob = coroutineScope.launch {
            pressProgress.animateTo(target, tween(durationMillis = FADE_DURATION_MS))
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val progress = pressProgress.value
        if (progress > 0f) {
            // Внутренний отступ, чтобы слой не касался краёв узла (мягкая «карточка» вместо полосы).
            val inset = PRESS_LAYER_INSET.toPx()
            // Размеры защищаем от отрицательных значений на очень маленьких узлах.
            val layerWidth = (size.width - inset * 2f).coerceAtLeast(0f)
            val layerHeight = (size.height - inset * 2f).coerceAtLeast(0f)
            // Цвет слоя берём из актуального content color узла; низкая альфа даёт мягкий отклик.
            drawRoundRect(
                color = currentValueOf(LocalContentColor),
                topLeft = Offset(inset, inset),
                size = Size(layerWidth, layerHeight),
                cornerRadius = CornerRadius(PRESS_LAYER_CORNER_RADIUS.toPx()),
                alpha = PRESS_LAYER_ALPHA * progress,
            )
        }
    }

    private companion object {
        const val PRESS_LAYER_ALPHA = 0.12f
        const val FADE_DURATION_MS = 90
        const val PRESSED_PROGRESS = 1f
        const val RELEASED_PROGRESS = 0f

        /** Радиус скругления слоя нажатия (~8 dp) — мягкий отклик вместо прямого угла. */
        val PRESS_LAYER_CORNER_RADIUS = 8.dp

        /** Отступ слоя нажатия от границ узла (~4 dp) по горизонтали и вертикали. */
        val PRESS_LAYER_INSET = 4.dp
    }
}
