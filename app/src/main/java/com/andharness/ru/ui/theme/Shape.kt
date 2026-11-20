package com.andharness.ru.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii from `DESIGN/tokens.css` §3:
 * `--r-sm: 8px`, `--r-md: 12px`, `--r-lg: 16px`, `--r-xl: 28px`, `--r-pill: 999px`.
 *
 * The design has no dedicated extra-small radius, so [Shapes.extraSmall] reuses the smallest
 * available token (8dp) instead of inventing a value.
 *
 * Material 3 shape scale — https://m3.material.io/styles/shape/shape-scale-tokens
 * Compose M3 theming — https://developer.android.com/develop/ui/compose/designsystems/material3
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), // --r-sm
    small = RoundedCornerShape(8.dp), // --r-sm
    medium = RoundedCornerShape(12.dp), // --r-md
    large = RoundedCornerShape(16.dp), // --r-lg
    extraLarge = RoundedCornerShape(28.dp), // --r-xl
)

/** Fully rounded pill (`--r-pill: 999px`), e.g. for chips and segmented controls. */
val PillShape = RoundedCornerShape(percent = 50)
