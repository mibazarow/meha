package com.andharness.ru.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight

/**
 * Resolved accent-panel colors for the active theme. Not an M3 role set, so it is computed by
 * [rememberAccentColors] from the scheme instead of being stored in [MaterialTheme].
 */
@Immutable
private data class AccentColors(
    val border: Color,
    val background: Brush,
    val label: Color,
    val content: Color,
)

/** Picks the accent family from the active scheme: green in dark, sky/indigo in light. */
@Composable
private fun rememberAccentColors(): AccentColors {
    val scheme = MaterialTheme.colorScheme
    // Allocation strategy: the colours are theme-driven, so the resolved scheme colours used below
    // are the `remember` keys — the AccentColors wrapper and its Brush.linearGradient are allocated
    // once per palette, not on every recomposition.
    // remember(keys): https://developer.android.com/reference/kotlin/androidx/compose/runtime/package-summary#remember(kotlin.Any,kotlin.Any,kotlin.Any,kotlin.Any,kotlin.Any)
    // and https://developer.android.com/develop/ui/compose/side-effects
    // Brush.linearGradient: https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Brush
    return remember(scheme.surface, scheme.primary, scheme.secondary, scheme.tertiary, scheme.onSurface) {
        // D4 keeps a fixed palette, so the surface luminance is a reliable light/dark signal.
        val dark = scheme.surface.luminance() < 0.5f
        if (dark) {
            val accent = scheme.primary // #22C55E
            AccentColors(
                border = accent.copy(alpha = AppTokens.AccentBorderAlphaDark),
                background = Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = AppTokens.AccentSurfaceTopAlphaDark),
                        scheme.secondary.copy(alpha = AppTokens.AccentSurfaceBottomAlphaDark),
                    )
                ),
                label = scheme.primary,
                content = scheme.onSurface,
            )
        } else {
            val accent = scheme.secondary // #00668B
            AccentColors(
                border = accent.copy(alpha = AppTokens.AccentBorderAlphaLight),
                background = Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = AppTokens.AccentSurfaceTopAlphaLight),
                        scheme.tertiary.copy(alpha = AppTokens.AccentSurfaceBottomAlphaLight),
                    )
                ),
                label = scheme.secondary,
                content = scheme.onSurface,
            )
        }
    }
}

/**
 * The accent panel from `DESIGN/components.css` `.panel--accent`: rounded surface with a soft tint
 * gradient, a subtle brand border and an uppercase label (`.ps-label` of the landing).
 *
 * The HTML `--accent-glow` box-shadow (`0 24px 60px -40px` accent 80%) was intentionally dropped:
 * Compose's elevation shadow exposes no blur/spread controls (only an elevation), so the CSS glow
 * could not be reproduced cleanly — it rendered as a hard tinted frame instead of a soft halo.
 * See https://developer.android.com/develop/ui/compose/graphics/draw/modifiers for the draw
 * modifiers (`background`/`border`) this panel is built from.
 *
 * @param label uppercase label rendered above [content]; keep it short ("Важно").
 * @param icon optional leading glyph for the label (Material Symbols in the design; any
 *   [ImageVector] here).
 * @param content panel body; it renders on the panel's surface content color.
 */
@Composable
fun AccentPanel(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    val colors = rememberAccentColors()
    val shape = MaterialTheme.shapes.large // --r-lg
    Column(
        modifier = modifier
            .background(brush = colors.background, shape = shape)
            .border(border = BorderStroke(AppTokens.BorderWidth, colors.border), shape = shape)
            .padding(AppTokens.PanelPadding)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTokens.SectionSpacing / 2)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.label,
                    modifier = Modifier.size(AppTokens.IconSizeXs)
                )
            }
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = AppTokens.CapsSpacing
                ),
                color = colors.label
            )
        }
        Spacer(modifier = Modifier.height(AppTokens.SectionSpacing))
        CompositionLocalProvider(LocalContentColor provides colors.content) {
            content()
        }
    }
}
