package com.andharness.ru.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.andharness.ru.ui.theme.BrandGold
import com.andharness.ru.ui.theme.BrandGoldBright
import com.andharness.ru.ui.theme.BrandGoldDeep
import com.andharness.ru.ui.theme.BrandGreen
import com.andharness.ru.ui.theme.BrandGreenBright
import com.andharness.ru.ui.theme.BrandGreenDeep
import com.andharness.ru.ui.theme.OnBrandGold
import com.andharness.ru.ui.theme.PillShape

// Allocation strategy: the CTA stops are fixed brand tokens from `ui/theme/Color.kt` (no theme or
// state inputs), so each gradient brush is allocated once at file scope instead of on every
// recomposition; `Brush` instances are immutable and safe to share.
// https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Brush#linearGradient(kotlin.Pair(kotlin.Float,androidx.compose.ui.graphics.Color),kotlin.Array((kotlin.Pair(kotlin.Float,androidx.compose.ui.graphics.Color))))
private val GreenCtaBrush = Brush.linearGradient(
    0f to BrandGreenBright,
    0.55f to BrandGreen,
    1f to BrandGreenDeep
)

private val GoldCtaBrush = Brush.linearGradient(
    0f to BrandGoldBright,
    0.55f to BrandGold,
    1f to BrandGoldDeep
)

/**
 * Filled primary CTA with the brand green gradient (`--btn-primary-bg`, D12): a pill-shaped M3
 * [Button] whose surface is a [Brush.linearGradient] of the landing-page stops. The disabled state
 * falls back to a flat neutral container so it cannot be mistaken for an active CTA.
 *
 * The gradient is drawn by a wrapping [Box] and the [Button] itself stays transparent, which keeps
 * M3 semantics, ripple indication and the minimum touch target intact. The [Button] fills the
 * [Box] width so its content is centred across the full gradient regardless of the caller's
 * `fillMaxWidth` modifier.
 *
 * The label is pure white (`#FFFFFF`) at 16sp/SemiBold per the design request; note that white on
 * the bright gradient stops is below WCAG AA contrast, the same caveat as the branded FAB.
 *
 * @param enabled when false the button is not clickable and shows the disabled container/content.
 */
@Composable
fun PrimaryGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = PillShape
    // Theme signal: the resolved `surface` luminance picks the light-gold vs dark-green CTA, the
    // same pattern used in `AccentPanel`. https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#(androidx.compose.ui.graphics.Color).luminance()
    val dark = scheme.surface.luminance() < 0.5f
    val background: Brush = if (enabled) {
        if (dark) GreenCtaBrush else GoldCtaBrush
    } else {
        SolidColor(scheme.surfaceContainerHighest)
    }

    Box(
        modifier = modifier
            .heightIn(min = AppTokens.ButtonMinHeight)
            .background(brush = background, shape = shape)
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = if (dark) Color.White else OnBrandGold,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = scheme.onSurface.copy(alpha = AppTokens.DisabledContentAlpha)
            ),
            contentPadding = PaddingValues(horizontal = AppTokens.ButtonHorizontalPadding),
            modifier = Modifier.fillMaxWidth().heightIn(min = AppTokens.ButtonMinHeight)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
