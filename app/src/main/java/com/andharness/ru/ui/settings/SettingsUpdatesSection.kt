package com.andharness.ru.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import com.andharness.ru.R
import com.andharness.ru.data.update.UpdateState
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.theme.BrandGold
import com.andharness.ru.ui.theme.UpdatesInkOnWhite

/**
 * «Обновления» section: the manual «Проверить обновления» row and its live status line, in a
 * rounded [SettingsSection] card. The actual updater dialogs live in [SettingsUpdateDialogs].
 *
 * This is the first block on «Настройки» and is deliberately highlighted (user request):
 * - LIGHT theme: the card is forced to pure white [Color.White] on the cream `--pv-paper` page,
 *   the row title/subtitle/chevron use near-black [UpdatesInkOnWhite], the leading Refresh icon sits on a
 *   brand-gold [BrandGold] circular bubble with a BLACK glyph, and the non-error status line carries
 *   the brand gold `primary` (`#F6C94C`).
 * - DARK theme: unchanged — every override parameter stays `null`, so the card keeps the normal
 *   `surfaceContainerLow` surface and the rows keep their theme roles.
 * Error status stays `colorScheme.error` in both themes (errors are never hidden).
 *
 * The light/dark pick uses the established fixed-palette signal `surface.luminance()` (D4 disables
 * dynamic color, exactly as in `ui/components/AccentPanel.kt`).
 *
 * Docs:
 * - `Color.luminance()` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#(androidx.compose.ui.graphics.Color).luminance()
 * - `Color` / `Color.White` / `Color(0xFF…)` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#Color()
 * - `Surface(color = …)` behind [SettingsSection.containerColor] —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Surface(androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Shape,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.unit.Dp,androidx.compose.ui.unit.Dp,androidx.compose.foundation.BorderStroke,kotlin.Function0)
 */
@Composable
internal fun SettingsUpdatesSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tracker: EnterAnimationTracker,
) {
    val scheme = MaterialTheme.colorScheme
    // D4 keeps a fixed palette, so the surface luminance is a reliable light/dark signal
    // (the same signal AccentPanel uses: dark when < 0.5).
    val lightTheme = scheme.surface.luminance() >= 0.5f
    SettingsSection(
        title = stringResource(R.string.settings_updates_title),
        modifier = Modifier.enterAnimation(
            index = 1,
            tracker = tracker,
            itemKey = "settings_updates_label"
        ),
        containerColor = if (lightTheme) Color.White else null,
    ) {
        SettingsClickRow(
            title = stringResource(R.string.settings_updates_check),
            subtitle = updaterSubtitle(state),
            leadingIcon = Icons.Filled.Refresh,
            onClick = viewModel::checkForUpdates,
            contentColor = if (lightTheme) UpdatesInkOnWhite else null,
            accentColor = if (lightTheme) Color.Black else null,
            leadingIconContainerColor = if (lightTheme) BrandGold else null,
        )
        if (state.updateStatusText != null) {
            Text(
                text = state.updateStatusText!!,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.updateStatusIsError) {
                    scheme.error
                } else {
                    scheme.primary
                }
            )
        }
    }
}

/**
 * Subtitle of the «Проверить обновления» row: the live updater phase when an action is running,
 * otherwise the installed version read from `PackageManager`.
 */
@Composable
private fun updaterSubtitle(state: SettingsUiState): String = when (state.updaterState) {
    UpdateState.Checking -> stringResource(R.string.settings_updates_checking)
    is UpdateState.Downloading -> stringResource(R.string.settings_updates_downloading)
    is UpdateState.InstallReady, is UpdateState.NeedsUnknownSources ->
        stringResource(R.string.settings_updates_ready)
    else -> stringResource(
        R.string.settings_updates_version,
        state.installedVersionName,
        state.installedVersionCode
    )
}
