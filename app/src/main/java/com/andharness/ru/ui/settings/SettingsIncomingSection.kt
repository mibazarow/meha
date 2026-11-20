package com.andharness.ru.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

/**
 * «Входящие SMS» section: the intercept toggle that also arms `SmsReceiver`, with the honest copy
 * about reading SMS text and the required «SMS» permission. Rendered as a rounded [SettingsSection].
 */
@Composable
internal fun SettingsIncomingSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tracker: EnterAnimationTracker,
) {
    SettingsSection(
        title = stringResource(R.string.settings_sms_title),
        modifier = Modifier.enterAnimation(
            index = 6,
            tracker = tracker,
            itemKey = "settings_sms_label"
        )
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_sms_label),
            subtitle = stringResource(R.string.settings_sms_hint),
            checked = state.incomingSmsEnabled,
            onCheckedChange = viewModel::onIncomingSmsEnabledChange
        )
    }
}
