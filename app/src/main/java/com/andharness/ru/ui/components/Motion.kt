package com.andharness.ru.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Переиспользуемый моушен-кит (D1: «лёгкие приятные анимации»). Значения один-в-один
 * перенесены из `DESIGN/tokens.css` §3 «Моушен» и из входной анимации HTML-макетов
 * `@keyframes and-fade-up` (`DESIGN/components.css:1288`), чтобы Compose-экраны не «выскакивали»
 * мгновенно, а открывались так же мягко, как валидированный HTML.
 *
 * Соответствие токенам (значения НЕ подобранны на глаз):
 * - [AppDurationFastMillis]  = `--dur-fast`      (120ms) — шаг каскада (stagger);
 * - [AppDurationSlowMillis]  = `--dur-slow`      (320ms) — длительность входа `and-fade-up`;
 * - [AppEasingEmphasized]    = `--ease-emphasized` (cubic-bezier(0.05, 0.7, 0.1, 1)) — именно
 *   она стоит у `and-fade-up` во всех HTML-экранах;
 * - [AppEnterOffset]         = `--sp-2` (8px) — `and-fade-up` сдвигает контент на `translateY`.
 *   Шаг каскада в HTML — `calc(var(--dur-fast) * n)`, т.е. [AppStaggerStepMillis].
 *
 * Ссылки (официальная документация Compose):
 * - Анимации: быстрый старт — https://developer.android.com/develop/ui/compose/animation/quick-guide
 * - Анимации по значению — https://developer.android.com/develop/ui/compose/animation/value-based
 * - `AnimatedVisibility`/`fadeIn`/`slideInVertically` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/animation/package-summary
 * - `MotionDurationScale` (масштаб системных анимаций, «уменьшение движения») —
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale
 * - `Settings.Global.ANIMATOR_DURATION_SCALE` (резервный источник того же значения) —
 *   https://developer.android.com/reference/android/provider/Settings.Global#ANIMATOR_DURATION_SCALE
 * - `Modifier.graphicsLayer` — https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary
 */

/** `--dur-fast`: 120ms. */
const val AppDurationFastMillis: Int = 120

/**
 * `--dur-slow`: 320ms. Это же — длительность входной анимации `and-fade-up`
 * (`animation: and-fade-up var(--dur-slow) ...`, см. `DESIGN/components.css:1288`).
 */
const val AppDurationSlowMillis: Int = 320

/** Задержка на соседний элемент каскада: в HTML `calc(var(--dur-fast) * n)`. */
const val AppStaggerStepMillis: Int = AppDurationFastMillis

/**
 * Верхняя граница индекса каскада. В HTML задержка задаётся только для первых 6 элементов
 * (`:nth-child(n + 6)` → 3 шага, `04-chat.html:90-94` → 5 шагов, `09-db-viewer.html:193-198`
 * → 6 шагов), дальше элементы идут одновременно. Тот же потолок — чтобы длинные списки
 * не ждали появления: максимум `6 * 120ms = 720ms`.
 */
const val AppMaxStaggerSteps: Int = 6

/**
 * `--ease-emphasized`: cubic-bezier(0.05, 0.7, 0.1, 1). Используется входной анимацией
 * (`and-fade-up`), поэтому [Modifier.enterAnimation] берёт именно её.
 */
val AppEasingEmphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** `--sp-2` = 8dp: на столько `and-fade-up` сдвигает контент по вертикали. */
val AppEnterOffset: Dp = 8.dp

/**
 * Хранит идентификаторы элементов, для которых входная анимация уже проиграла. Нужен, чтобы
 * `LazyColumn` не повторял анимацию при переиспользовании/пересоздании элементов во время
 * скролла: элемент анимируется ровно один раз за визит экрана. Не является состоянием Compose
 * (обычный [MutableSet]) и потому не вызывает рекомпозиций.
 */
class EnterAnimationTracker {
    private val animatedKeys = mutableSetOf<Any>()

    /** true — ключ виден впервые (нужно анимировать); false — уже анимировался. */
    internal fun claim(key: Any): Boolean = animatedKeys.add(key)
}

/**
 * Системный масштаб анимаций. `0f` означает «анимации отключены» (специальные возможности
 * «Убрать анимацию» / «Animation off» в параметрах разработчика) — тогда входная анимация
 * пропускается и контент показывается сразу.
 *
 * Основной источник — [MotionDurationScale] из корутинного контекста Recomposer'а (так сам
 * Compose определяет уменьшение движения); тот же элемент устанавливает `WindowRecomposer` из
 * `Settings.Global.ANIMATOR_DURATION_SCALE`. Если элемента в контексте нет, читаем системную
 * настройку напрямую как резервный источник.
 */
@Composable
fun rememberMotionDurationScale(): Float {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        scope.coroutineContext[MotionDurationScale]?.scaleFactor
            ?: runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                )
            }.getOrDefault(1f)
    }
}

/**
 * Мягкий вход элемента: прозрачность 0→1 и сдвиг вверх на [AppEnterOffset] за
 * [AppDurationSlowMillis] с кривой [AppEasingEmphasized] — перенос `@keyframes and-fade-up`.
 * Необязательный [index] задаёт каскадную задержку ([AppStaggerStepMillis] на шаг, не более
 * [AppMaxStaggerSteps] шагов), поэтому списки появляются друг за другом, а не все сразу.
 *
 * Анимация запускается ровно один раз за время жизни элемента: значение «анимировать ли»
 * запоминается через `remember`, а при переданном [tracker] дополнительно дедуплицируется по
 * [itemKey] — это защищает от повторов при переиспользовании элементов `LazyColumn` при скролле.
 *
 * При отключённых системных анимациях ([rememberMotionDurationScale] == 0f) элемент показывается
 * сразу, без слоя и без запуска анимации.
 *
 * Реализация дешёвая: одна [Animatable] на элемент, изменения применяются в
 * [graphicsLayer] (чтение состояния в фазе отрисовки, без рекомпозиции на кадр и без аллокаций
 * на кадр). Слой снимается сразу после завершения анимации.
 *
 * @param index позиция элемента в каскаде (обычно индекс в списке).
 * @param enabled если false — элемент показывается сразу (например, не самый новый элемент).
 * @param tracker необязательный общий трекер «уже анимированных» ключей для списков.
 * @param itemKey ключ элемента для [tracker]; null — анимировать один раз за композицию.
 */
@Composable
fun Modifier.enterAnimation(
    index: Int = 0,
    enabled: Boolean = true,
    tracker: EnterAnimationTracker? = null,
    itemKey: Any? = null,
): Modifier {
    val motionScale = rememberMotionDurationScale()

    // Решение «анимировать ли» принимается один раз на элемент (remember по ключу): при
    // переиспользовании LazyColumn remember пересоздаётся, но трекер уже содержит ключ — повтор
    // не запускается. Для контейнера (без ключа) анимация идёт один раз за композицию экрана.
    val shouldAnimate = remember(itemKey) {
        when {
            !enabled || motionScale <= 0f -> false
            tracker != null && itemKey != null -> tracker.claim(itemKey)
            else -> true
        }
    }
    val progress = remember(itemKey) { Animatable(if (shouldAnimate) 0f else 1f) }
    var layerNeeded by remember(itemKey) { mutableStateOf(shouldAnimate) }

    LaunchedEffect(shouldAnimate, index, itemKey) {
        if (shouldAnimate) {
            val stagger = index.coerceIn(0, AppMaxStaggerSteps) * AppStaggerStepMillis
            if (stagger > 0) delay(stagger.toLong())
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = AppDurationSlowMillis,
                    easing = AppEasingEmphasized
                )
            )
            // Анимация закончилась — слой больше не нужен: снимаем, чтобы он не стоил ничего
            // при дальнейшем скролле/рекомпозициях.
            layerNeeded = false
        }
    }

    if (!layerNeeded) return this
    return this.graphicsLayer {
        // Чтение progress.value в фазе отрисовки: кадры анимации не вызывают рекомпозицию.
        val value = progress.value
        alpha = value
        translationY = (1f - value) * AppEnterOffset.toPx()
    }
}
