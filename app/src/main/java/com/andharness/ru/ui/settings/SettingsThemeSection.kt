package com.andharness.ru.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.theme.ThemeMode
import com.andharness.ru.ui.theme.ThemeViewModel

/**
 * «Оформление» section: the three-way theme selector (Light / Dark / System). Rendered as a rounded
 * [SettingsSection] card whose choice row is the shared, compact [SettingsSegmentedSelector] (all
 * three options on one line, green `primaryContainer` selection instead of the default sky-blue
 * `secondaryContainer`). The options, default and persistence are unchanged.
 *
 * Docs: Material 3 segmented buttons (single-choice semantics) —
 * https://developer.android.com/develop/ui/compose/components/segmented-button
 * `collectAsStateWithLifecycle` —
 * https://developer.android.com/reference/kotlin/androidx/lifecycle/compose/package-summary
 */
@Composable
internal fun SettingsThemeSection(
    themeViewModel: ThemeViewModel,
    tracker: EnterAnimationTracker,
) {
    val mode by themeViewModel.mode.collectAsStateWithLifecycle()

    SettingsSection(
        title = stringResource(R.string.settings_theme_title),
        modifier = Modifier.enterAnimation(
            index = 2,
            tracker = tracker,
            itemKey = "settings_theme_label"
        )
    ) {
        Text(
            text = stringResource(R.string.settings_theme_label),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        val modes = listOf(
            ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
            ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
            ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system)
        )
        SettingsSegmentedSelector(
            options = modes,
            selected = mode,
            onSelect = themeViewModel::setMode
        )
        SettingsHelperText(stringResource(R.string.settings_theme_hint))
    }
}
