package com.andharness.ru.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.andharness.ru.R

/**
 * App typography: **Inter only** (D7) — hierarchy is built from weight (400/500/600/700) and
 * size, not from a second display face. The four static instances below are bundled in
 * `res/font/` so the app renders identically offline and on every device (no system-font
 * fallback), and all four carry Cyrillic (verified against the `cmap` table).
 *
 * Font files: Inter 4.1 (Rasmus Andersson), `extras/ttf/Inter-{Regular,Medium,SemiBold,Bold}.ttf`
 * from https://github.com/rsms/inter/releases/tag/v4.1 — SIL Open Font License 1.1, shipped in
 * `assets/licenses/Inter-OFL.txt`.
 *
 * Sizes/line-heights/letter-spacing come from `DESIGN/tokens.css` §3 (`--fs-*`, `--lh-*`, `--ls-*`).
 * M3 type roles — https://m3.material.io/styles/typography/type-scale-tokens
 * Compose custom fonts — https://developer.android.com/develop/ui/compose/text/fonts
 */

/** Inter, bundled as four static weights (400/500/600/700). */
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * Design type scale mapped onto the Material 3 roles. The design token layer defines a single
 * display size (32sp) and a single caption size (12sp); roles without a dedicated token reuse the
 * nearest token size rather than inventing a value.
 */
val AppTypography = Typography(
    // --fs-display 32 / 40 / -0.4 (the design's only display size)
    displayLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),

    // Headlines reuse the display / title sizes (no separate headline tokens exist).
    headlineLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp,
    ),

    // --fs-title-lg 22 / 28 / -0.2, --fs-title 18 / 24 / -0.1, --fs-title-sm 16 / 22 / 0
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),

    // --fs-body-lg 16 / 24 / 0, --fs-body 14 / 20 / 0.1, --fs-caption 12 / 16
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),

    // --fs-label 13 / 16 / 0.1, --fs-caption 12 / 16, --fs-label-sm 11 / 16 / 0.4
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)
