package com.andharness.ru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.data.update.UpdateState
import com.andharness.ru.data.update.hasKnownTotal
import com.andharness.ru.data.update.progressFraction
import com.andharness.ru.domain.formatting.ByteFormat
import com.andharness.ru.domain.update.UpdatePolicy

/**
 * The in-app updater dialogs: offer → download → (unknown sources) → install. Each dialog reflects
 * exactly one [UpdateState], so only one is visible at a time. All copy is localized (RU/EN).
 *
 * Compose `AlertDialog` / `LinearProgressIndicator`:
 * https://developer.android.com/develop/ui/compose/components/dialog
 * https://developer.android.com/develop/ui/compose/components/progress
 */
@Composable
internal fun SettingsUpdateDialogs(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val available = state.availableUpdate
    if (available != null) {
        val mandatory = state.isUpdateMandatory
        AlertDialog(
            // A mandatory update cannot be dismissed by tapping outside.
            onDismissRequest = { if (!mandatory) viewModel.dismissUpdate() },
            title = {
                Text(
                    stringResource(
                        R.string.update_available_title,
                        available.versionName.ifBlank { available.versionCode.toString() }
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (available.notes.isNotBlank()) {
                        Text(available.notes)
                    }
                    Text(
                        stringResource(
                            R.string.update_size,
                            ByteFormat.humanBytes(available.sizeBytes)
                        )
                    )
                    if (mandatory) {
                        Text(stringResource(R.string.update_mandatory))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::downloadUpdate) {
                    Text(stringResource(R.string.update_button))
                }
            },
            dismissButton = {
                // Mandatory updates hide the postpone action entirely.
                if (!mandatory) {
                    TextButton(onClick = viewModel::dismissUpdate) {
                        Text(stringResource(R.string.update_later))
                    }
                }
            }
        )
    }

    val downloading = state.updaterState as? UpdateState.Downloading
    if (downloading != null) {
        val known = downloading.hasKnownTotal
        val percent = (downloading.progressFraction * 100f).toInt().coerceIn(0, 100)
        AlertDialog(
            // A download in progress must not be dismissed into an inconsistent state.
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_download_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (known) {
                        val progressDesc = stringResource(R.string.update_progress_desc, percent)
                        LinearProgressIndicator(
                            progress = { downloading.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = progressDesc }
                        )
                        Text(
                            stringResource(
                                R.string.update_progress,
                                percent,
                                ByteFormat.humanBytes(downloading.downloadedBytes),
                                ByteFormat.humanBytes(downloading.totalBytes)
                            )
                        )
                    } else {
                        val progressDesc = stringResource(R.string.update_progress_desc_unknown)
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = progressDesc }
                        )
                        Text(
                            stringResource(
                                R.string.update_progress_unknown,
                                ByteFormat.humanBytes(downloading.downloadedBytes)
                            )
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    val needsPermission = state.updaterState as? UpdateState.NeedsUnknownSources
    if (needsPermission != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissUpdate,
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_text)) },
            confirmButton = {
                TextButton(onClick = viewModel::requestUnknownSources) {
                    Text(stringResource(R.string.update_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUpdate) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }

    val installReady = state.updaterState as? UpdateState.InstallReady
    if (installReady != null) {
        val mandatory = UpdatePolicy.isMandatory(installReady.manifest, state.installedVersionCode)
        AlertDialog(
            onDismissRequest = { if (!mandatory) viewModel.dismissUpdate() },
            title = { Text(stringResource(R.string.update_install_title)) },
            text = { Text(stringResource(R.string.update_install_text)) },
            confirmButton = {
                TextButton(onClick = viewModel::installUpdate) {
                    Text(stringResource(R.string.update_install))
                }
            },
            dismissButton = {
                if (!mandatory) {
                    TextButton(onClick = viewModel::dismissUpdate) {
                        Text(stringResource(R.string.update_later))
                    }
                }
            }
        )
    }
}
