package com.andharness.ru.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.PrimaryGradientButton
import com.andharness.ru.ui.components.enterAnimation

/**
 * The form-actions block: the error line, the brand «Сохранить» CTA and the destructive
 * «Сбросить все данные» control with its confirmation dialog. [onReset] runs after a confirmed reset
 * (navigation side effect). Rendered as a headerless rounded [SettingsSection] (`title = null`) —
 * there is no system-prompt field here, so the old «Системный промпт» heading was a stray leftover.
 */
@Composable
internal fun SettingsPromptActionsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tracker: EnterAnimationTracker,
    onReset: () -> Unit,
) {
    SettingsSection(
        title = null,
        modifier = Modifier.enterAnimation(
            index = 10,
            tracker = tracker,
            itemKey = "settings_prompt_label"
        )
    ) {
        if (state.error != null) {
            Text(
                text = state.error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Brand primary CTA (DESIGN `.btn--filled`): green gradient with light content. The transient
        // save-state label logic and the testTag are unchanged.
        PrimaryGradientButton(
            text = when {
                state.isLoading -> stringResource(R.string.settings_save_saving)
                state.saved -> stringResource(R.string.settings_save_saved)
                else -> stringResource(R.string.settings_save)
            },
            onClick = viewModel::save,
            enabled = !state.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_save_button")
        )

        // Destructive outlined action (DESIGN `.btn--outlined.btn--danger`): error-colored content
        // and border, transparent container; the reset dialog itself is unchanged.
        OutlinedButton(
            onClick = { viewModel.confirmReset() },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Text(stringResource(R.string.settings_reset_button))
        }
    }

    if (state.showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelReset() },
            title = { Text(stringResource(R.string.settings_reset_dialog_title)) },
            text = { Text(stringResource(R.string.settings_reset_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAllData()
                    onReset()
                }) {
                    Text(stringResource(R.string.settings_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelReset() }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}
