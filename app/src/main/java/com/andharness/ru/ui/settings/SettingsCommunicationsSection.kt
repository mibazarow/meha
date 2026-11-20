package com.andharness.ru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.domain.communications.CommunicationsMode
import com.andharness.ru.domain.communications.speech.SpeechModel
import com.andharness.ru.domain.communications.speech.SpeechModelDownloadState
import com.andharness.ru.domain.communications.speech.SpeechModelKind
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

/**
 * «Связи» section: the persisted Communications config (mode, cloud-transcript opt-in, whisper
 * model, retention/cache limits) plus the honest privacy/consent note. Every control sits on the
 * shared settings grid inside one rounded [SettingsSection] card: choice rows use the compact
 * [SettingsSegmentedSelector] (no default sky-blue pills), switch rows fold their hint into the
 * inline subtitle so labels/helpers stay on one column, and numeric limits use [SettingsStepperRow].
 * Every control still writes straight into
 * [com.andharness.ru.data.communications.CommunicationsConfigStore] through [SettingsViewModel].
 *
 * Docs: Material 3 segmented buttons —
 * https://developer.android.com/develop/ui/compose/components/segmented-button
 */
@Composable
internal fun SettingsCommunicationsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tracker: EnterAnimationTracker,
) {
    SettingsSection(
        title = stringResource(R.string.settings_comm_title),
        modifier = Modifier.enterAnimation(
            index = 7,
            tracker = tracker,
            itemKey = "settings_comm_label"
        )
    ) {
        // --- Recording mode -----------------------------------------------------------------
        Text(
            text = stringResource(R.string.settings_comm_mode_label),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        SettingsSegmentedSelector(
            options = CommunicationsMode.entries.map { it to communicationsModeLabel(it) },
            selected = state.communicationsMode,
            onSelect = viewModel::onCommunicationsModeChange
        )
        SettingsHelperText(stringResource(R.string.settings_comm_mode_hint))

        // --- Cloud transcripts opt-in -------------------------------------------------------
        SettingsSwitchRow(
            title = stringResource(R.string.settings_comm_cloud_label),
            subtitle = stringResource(R.string.settings_comm_cloud_hint),
            checked = state.communicationsSendTranscriptsToCloud,
            onCheckedChange = viewModel::onCommunicationsSendTranscriptsToCloudChange
        )

        // --- Whisper model + download -------------------------------------------------------
        Text(
            text = stringResource(R.string.settings_comm_whisper_label),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        val whisperModels = SpeechModel.entries.filter { it.kind == SpeechModelKind.WHISPER }
        SettingsSegmentedSelector(
            options = whisperModels.map {
                it.id to "${shortModelName(it)} (${SpeechModel.humanBytes(it.approxBytes)})"
            },
            selected = state.communicationsWhisperModelId,
            onSelect = viewModel::onCommunicationsWhisperModelChange
        )
        WhisperModelPanel(
            state = state,
            viewModel = viewModel,
            models = whisperModels
        )

        // --- Retention + audio cache --------------------------------------------------------
        SettingsStepperRow(
            label = stringResource(R.string.settings_comm_keep_audio_days),
            valueText = state.communicationsKeepAudioDays.toString(),
            onDecrease = {
                viewModel.onCommunicationsKeepAudioDaysChange(
                    state.communicationsKeepAudioDays - 1
                )
            },
            onIncrease = {
                viewModel.onCommunicationsKeepAudioDaysChange(
                    state.communicationsKeepAudioDays + 1
                )
            }
        )
        SettingsStepperRow(
            label = stringResource(R.string.settings_comm_cache_limit),
            valueText = state.communicationsAudioCacheLimitMb.toString(),
            onDecrease = {
                viewModel.onCommunicationsAudioCacheLimitMbChange(
                    state.communicationsAudioCacheLimitMb - 50
                )
            },
            onIncrease = {
                viewModel.onCommunicationsAudioCacheLimitMbChange(
                    state.communicationsAudioCacheLimitMb + 50
                )
            }
        )
        SettingsHelperText(stringResource(R.string.settings_comm_retention_hint))
        SettingsStepperRow(
            label = stringResource(R.string.settings_comm_bg_minutes),
            valueText = state.communicationsMaxProcessMinutesPerDay.toString(),
            onDecrease = {
                viewModel.onCommunicationsMaxProcessMinutesPerDayChange(
                    state.communicationsMaxProcessMinutesPerDay - 15
                )
            },
            onIncrease = {
                viewModel.onCommunicationsMaxProcessMinutesPerDayChange(
                    state.communicationsMaxProcessMinutesPerDay + 15
                )
            }
        )
        SettingsHelperText(stringResource(R.string.settings_comm_bg_minutes_hint))

        // --- Privacy / consent note ---------------------------------------------------------
        SettingsHelperText(stringResource(R.string.settings_comm_privacy))
    }
}

/**
 * Download / verify / delete panel for the currently selected whisper model. Progress and failure
 * text come straight from the single [SpeechModelDownloadState] the store publishes, so the UI never
 * shows a guessed percentage.
 */
@Composable
private fun WhisperModelPanel(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    models: List<SpeechModel>,
) {
    val selected = models.firstOrNull { it.id == state.communicationsWhisperModelId }
        ?: models.firstOrNull()
        ?: return
    val download = state.communicationsDownload
    val isDownloaded = selected.id in state.communicationsDownloadedModelIds

    SettingsHelperText(
        text = stringResource(
            R.string.settings_comm_whisper_desc,
            whisperDisplayName(selected),
            SpeechModel.humanBytes(selected.approxBytes)
        )
    )

    when {
        download is SpeechModelDownloadState.Downloading && download.modelId == selected.id -> {
            val percent = SpeechModel.progressPercent(download.downloadedBytes, download.totalBytes)
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(
                    R.string.settings_model_downloading,
                    percent,
                    SpeechModel.humanBytes(download.downloadedBytes),
                    SpeechModel.humanBytes(download.totalBytes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = viewModel::onCommunicationsCancelWhisperDownload) {
                Text(stringResource(R.string.settings_cancel))
            }
        }

        download is SpeechModelDownloadState.Verifying && download.modelId == selected.id -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.settings_model_verifying),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        download is SpeechModelDownloadState.Failed && download.modelId == selected.id -> {
            Text(
                text = stringResource(R.string.settings_model_failed, download.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::onCommunicationsDownloadWhisperModel) {
                    Text(stringResource(R.string.settings_retry))
                }
                OutlinedButton(onClick = viewModel::onCommunicationsDeleteWhisperModel) {
                    Text(stringResource(R.string.settings_delete))
                }
            }
        }

        isDownloaded -> {
            Text(
                text = stringResource(R.string.settings_comm_downloaded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedButton(onClick = viewModel::onCommunicationsDeleteWhisperModel) {
                Text(stringResource(R.string.settings_delete_model))
            }
        }

        else -> {
            Button(onClick = viewModel::onCommunicationsDownloadWhisperModel) {
                Text(stringResource(R.string.settings_download_model))
            }
        }
    }
}

/** Short UI token for a whisper model: `base`/`small` instead of the long Russian display name. */
private fun shortModelName(model: SpeechModel): String = when (model) {
    SpeechModel.WHISPER_BASE -> "base"
    SpeechModel.WHISPER_SMALL -> "small"
    else -> model.id
}

/** Localized display name for a whisper model (the domain label is Russian-only). */
@Composable
private fun whisperDisplayName(model: SpeechModel): String = when (model) {
    SpeechModel.WHISPER_BASE -> stringResource(R.string.settings_whisper_base)
    SpeechModel.WHISPER_SMALL -> stringResource(R.string.settings_whisper_small)
    else -> model.displayName
}

/** Localized label for a communications mode (the domain label is Russian-only). */
@Composable
private fun communicationsModeLabel(mode: CommunicationsMode): String = stringResource(
    when (mode) {
        CommunicationsMode.OFF -> R.string.settings_comm_mode_off
        CommunicationsMode.CHARGING -> R.string.settings_comm_mode_charging
        CommunicationsMode.ALWAYS -> R.string.settings_comm_mode_always
    }
)
