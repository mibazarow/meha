package com.andharness.ru.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The unified content style for the Settings screens (redesign «unify internal pages»).
 *
 * Every block on «Настройки» / «Провайдеры» is now a [SettingsSection]: a clear, human-friendly
 * title (theme `titleMedium`, SemiBold) above a single rounded surface card. The [title] is
 * nullable: when it is `null` the header (and its spacing) is skipped entirely, so a section can be
 * a bare card — used by the form-actions block, which has no meaningful heading. Inside the card the
 * rows and helpers share one rhythm — the card owns the horizontal padding and a 12dp vertical
 * gap, and the row components below ([SettingsSwitchRow], [SettingsClickRow]) carry no outer
 * padding, so a section always reads as one tidy, rounded block instead of a loose divider column.
 * This mirrors the redesigned chat list, which uses rounded M3 surfaces and theme typography rather
 * than the legacy `AppTokens` divider/label kit.
 *
 * Docs:
 * - `Surface` (`shape` / `color`; content is clipped to the shape) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Surface(androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Shape,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.unit.Dp,androidx.compose.ui.unit.Dp,androidx.compose.foundation.BorderStroke,kotlin.Function0)
 * - Material 3 shape scale (`Shapes.large` = 16dp) —
 *   https://m3.material.io/styles/shape/shape-scale-tokens
 * - Material 3 typography roles (`titleMedium` / `bodyLarge` / `bodySmall`) —
 *   https://m3.material.io/styles/typography/type-scale-tokens
 * - `Modifier.toggleable` (whole-row switch target with `Role.Switch` semantics and a mergeable
 *   state description) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/selection/package-summary#(androidx.compose.ui.Modifier).toggleable(kotlin.Boolean,kotlin.Boolean,androidx.compose.ui.semantics.Role,kotlin.Function1)
 * - `Modifier.clickable` (`role` / `onClick` overload) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary#(androidx.compose.ui.Modifier).clickable(kotlin.Boolean,kotlin.String,androidx.compose.ui.semantics.Role,kotlin.Function0)
 * - `Modifier.background(color, shape)` (leading-icon bubble) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary#(androidx.compose.ui.Modifier).background(androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Shape)
 */
@Composable
internal fun SettingsSection(
    title: String?,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = containerColor ?: MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

/**
 * A whole-row switch: [title] (and optional [subtitle]) on the left, a Material 3 [Switch] on the
 * right. The entire row is the tap target ([Modifier.toggleable] with `Role.Switch`), so a novice
 * can toggle from anywhere on the line; the `Switch` itself passes `onCheckedChange = null` because
 * the row already exposes the toggle semantics (the recommended Compose pattern — a second handler
 * would duplicate the accessibility action).
 *
 * Docs: `Switch` (`onCheckedChange = null` when the row owns the state) —
 * https://developer.android.com/develop/ui/compose/components/switch
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Short helper copy under an option, in the muted `bodySmall` style shared by all sections. */
@Composable
internal fun SettingsHelperText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * A navigation/action row: optional leading glyph in a rounded bubble, a title with an optional
 * subtitle, and a trailing chevron. The whole row is one accessible button target.
 *
 * By default the row is transparent on the section card ([containerColor] `null`). Pass a
 * [containerColor] to paint a highlighted row (the fill is clipped to [MaterialTheme.shapes.medium]
 * and a modest 12dp horizontal padding gives the highlight breathing room); pass a [contentColor] to
 * keep the title/subtitle/chevron and the leading bubble readable on that fill. Pass an
 * [accentColor] to give the leading icon its own brand tint (e.g. black on the gold bubble of the
 * highlighted «Обновления» card); `null` keeps the icon on the [contentColor]/`onSurfaceVariant`
 * default, so existing rows are unaffected. Pass a [leadingIconContainerColor] to override only the
 * circular bubble behind the leading icon (e.g. the brand gold on «Обновления»); `null` keeps the
 * default bubble.
 */
@Composable
internal fun SettingsClickRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
    accentColor: Color? = null,
    leadingIconContainerColor: Color? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (containerColor != null) {
                    Modifier.background(containerColor, MaterialTheme.shapes.medium)
                } else {
                    Modifier
                }
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(
                horizontal = if (containerColor != null) 12.dp else 0.dp,
                vertical = 4.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = leadingIconContainerColor
                            ?: contentColor?.copy(alpha = 0.14f)
                            ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = accentColor ?: contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The single segmented selector used by every settings choice (theme, language, recording mode,
 * whisper model). It replaces `SingleChoiceSegmentedButtonRow` + `SegmentedButton`, whose default
 * active container is `MaterialTheme.colorScheme.secondaryContainer` — in this brand palette that
 * is a bright sky blue (`#38BDF8` light / `#1DAFEA` dark), the "jarring blue pill" the redesign
 * calls out. Here the selected segment uses the **green** `primaryContainer` / `onPrimaryContainer`
 * pair instead (high contrast in both themes), and the unselected label stays muted
 * (`onSurfaceVariant`).
 *
 * Layout is deliberately compact and grid-safe: the options sit on one [Row] with `weight(1f)` each
 * (equal width, never a second line), `labelMedium` text with `maxLines = 1` / `softWrap = false`
 * (a long label ellipsizes rather than wrapping), and tight padding. The track is a pill filled with
 * the neutral `surfaceContainerHighest` so it reads as one control inside a [SettingsSection] card.
 *
 * Docs:
 * - `Modifier.selectable` (single-choice semantics; the analog of `SegmentedButton`'s row role) —
 *   https://developer.android.com/develop/ui/compose/components/segmented-button
 * - `Modifier.weight` (`RowScope`, equal-width segments) —
 *   https://developer.android.com/develop/ui/compose/layouts/basics
 * - `Modifier.clip(shape)` + `Modifier.background(color, shape)` (pill segments) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary#(androidx.compose.ui.Modifier).background(androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Shape)
 * - Material 3 color roles (`primaryContainer` / `onPrimaryContainer` / `surfaceContainerHighest`) —
 *   https://m3.material.io/styles/color/roles
 */
@Composable
internal fun <T> SettingsSegmentedSelector(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) }
                    )
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * A compact `− value +` stepper on the shared settings grid: the [label] takes the free space so
 * the value and buttons hug the same right edge as the [SettingsSwitchRow] switches. The round
 * buttons are neutral (`surfaceContainerHighest`), not tinted, so numeric rows never introduce a
 * stray accent colour. Values are still clamped by the store, so out-of-range taps are harmless.
 *
 * Docs: Material 3 `IconButton` (enforces the 48dp minimum touch target around the 40dp button) —
 * https://developer.android.com/develop/ui/compose/components/icon-button
 */
@Composable
internal fun SettingsStepperRow(
    label: String,
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        SettingsStepperButton(symbol = "\u2212", onClick = onDecrease)
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 44.dp)
        )
        SettingsStepperButton(symbol = "+", onClick = onIncrease)
    }
}

@Composable
private fun SettingsStepperButton(
    symbol: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
