package com.andharness.ru.ui.components

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * Geometry and alpha tokens of the shared UI kit — the dp/em equivalents of `DESIGN/tokens.css`.
 *
 * Extracted from the former single `AppComponents.kt` so each shared component lives in its own
 * file while still reading the same named tokens (no magic numbers duplicated per component).
 * Stateless constants only.
 */
internal object AppTokens {
    // --- Geometry (DESIGN/tokens.css §3) --------------------------------------------------------
    val SectionSpacing = 8.dp // --sp-2
    val PanelPadding = 24.dp // --sp-5
    val CapsSpacing = 0.14.em // --ls-caps
    val IconSizeXs = 13.dp // --icon-size-xs (panel/section label glyph)
    val ButtonMinHeight = 48.dp // --btn-h
    val ButtonHorizontalPadding = 24.dp // --sp-5
    val BorderWidth = 1.dp
    const val DisabledContentAlpha = 0.38f

    // --- Accent panel alphas (DESIGN/tokens.css `--accent-*`) ------------------------------------
    // Dark theme is brand-green (D2); the light theme deliberately switches the accent family to
    // sky/indigo because a green tint/border read as "mould" on the light surface (D12 addendum).
    const val AccentBorderAlphaDark = 0.26f // --accent-border: green 26%
    const val AccentSurfaceTopAlphaDark = 0.11f // --accent-surface: green 11%
    const val AccentSurfaceBottomAlphaDark = 0.05f // → sky 5%
    const val AccentBorderAlphaLight = 0.32f // --accent-border: sky 32%
    const val AccentSurfaceTopAlphaLight = 0.10f // --accent-surface: sky 10%
    const val AccentSurfaceBottomAlphaLight = 0.05f // → indigo (tertiary) 5%
}
