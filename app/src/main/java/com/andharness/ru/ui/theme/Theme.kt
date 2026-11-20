package com.andharness.ru.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App-specific colors that have no Material 3 role (currently the chat bubble pair). Provided
 * through a [staticCompositionLocalOf] so screens read them as `LocalBubbleColors.current`; the
 * value follows the active scheme automatically.
 */
val LocalBubbleColors = staticCompositionLocalOf { DarkBubbleColors }

/**
 * ANDharness theme. Always uses the fixed brand palette from [DarkColorScheme] / [LightColorScheme]
 * — **dynamic color / Material You is off** (D4), so `dynamicDark/LightColorScheme` is never used.
 *
 * The theme is selected by [ThemeMode] (persisted in [ThemeStore], default [ThemeMode.DARK] per D3);
 * [ThemeMode.SYSTEM] follows the OS setting. Applying it through one composable at the root keeps
 * the switch app-wide whenever the store changes.
 *
 * Material 3 theming — https://developer.android.com/develop/ui/compose/designsystems/material3
 * `MaterialTheme` — https://developer.android.com/reference/kotlin/androidx/compose/material3/MaterialTheme
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Theme(
    themeMode: ThemeMode = ThemeStore.DEFAULT_MODE,
    content: @Composable () -> Unit
) {
    val darkTheme = themeMode.isDark(isSystemInDarkTheme())
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val bubbleColors = if (darkTheme) DarkBubbleColors else LightBubbleColors

    // На тач-экране наведения нет, поэтому hover-подсветка не нужна нигде. Гасим её двумя
    // механизмами, сохраняя отклик на нажатие:
    // 1) LocalIndication — для `Modifier.clickable` без явной индикации (фундамент); наш
    //    NoHoverIndication рисует мягкий скруглённый слой. Провайдится ВНУТРИ `MaterialTheme`
    //    (см. ниже), потому что M3 сам переопределяет LocalIndication своим `ripple(...)`;
    // 2) LocalRippleConfiguration — для всех M3-компонентов (Button, IconButton, Card, Chip,
    //    Switch, TopAppBar, ListItem и т.д.), которые передают собственный `ripple()`
    //    и НЕ читают LocalIndication. Обнуляем только hover-альфу, press/drag/focus без изменений.
    // Ссылки: https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary
    //         https://developer.android.com/reference/kotlin/androidx/compose/material3/RippleConfiguration
    val defaultRippleAlpha = RippleDefaults.RippleAlpha
    val noHoverRippleAlpha = RippleAlpha(
        defaultRippleAlpha.draggedAlpha,
        defaultRippleAlpha.focusedAlpha,
        0f, // hoveredAlpha — именно то, что показывалось при движении указателя.
        defaultRippleAlpha.pressedAlpha,
    )

    CompositionLocalProvider(
        LocalBubbleColors provides bubbleColors,
        LocalRippleConfiguration provides RippleConfiguration(
            color = Color.Unspecified, // Unspecified ⇒ цвет берётся из content color, как раньше.
            rippleAlpha = noHoverRippleAlpha,
        ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
        ) {
            // ВАЖНО: M3 `MaterialTheme` сам выполняет `LocalIndication provides ripple(...)`
            // (MaterialTheme.kt) и тем самым перекрывает провайдер, размещённый снаружи.
            // Поэтому наш слой нажатия провайдится ВНУТРИ `MaterialTheme` — иначе
            // `Modifier.clickable` (например, строка чата) получал бы M3-ripple с его резким
            // прямоугольником во всю ширину.
            CompositionLocalProvider(LocalIndication provides NoHoverIndication) {
                content()
            }
        }
    }
}
