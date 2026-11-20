package com.andharness.ru.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

/**
 * «Плавающий помощник» section: the honest overlay toggle (requires `SYSTEM_ALERT_WINDOW`), the
 * live permission status and the prompt message when the grant is missing. Rendered as a rounded
 * [SettingsSection] with a whole-row switch.
 */
@Composable
internal fun SettingsOverlaySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tracker: EnterAnimationTracker,
) {
    SettingsSection(
        title = stringResource(R.string.settings_overlay_title),
        modifier = Modifier.enterAnimation(
            index = 8,
            tracker = tracker,
            itemKey = "settings_overlay_label"
        )
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_overlay_label),
            checked = state.overlayAssistantEnabled,
            onCheckedChange = viewModel::onOverlayAssistantEnabledChange
        )
        SettingsHelperText(stringResource(R.string.settings_overlay_hint))
        SettingsHelperText(
            text = if (state.overlayAssistantPermissionGranted) {
                stringResource(R.string.settings_overlay_granted)
            } else {
                stringResource(R.string.settings_overlay_not_granted)
            }
        )
        if (state.overlayPermissionMessage != null) {
            Text(
                text = state.overlayPermissionMessage!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
