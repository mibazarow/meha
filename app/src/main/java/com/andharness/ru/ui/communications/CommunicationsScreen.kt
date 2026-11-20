package com.andharness.ru.ui.communications

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.TranscriptStatus
import com.andharness.ru.ui.components.CenterMessage
import com.andharness.ru.ui.components.searchFieldColors
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection

/**
 * «Связи» — the user-facing list of local recordings and their transcripts.
 *
 * Content is re-skinned to the unified internal-page style matching the Settings/Memory redesign:
 * the explainer is a [SettingsSection] with muted [SettingsHelperText], and every recording is a
 * rounded [Surface] card (`shapes.large`, `surfaceContainerLow`) instead of a flat row behind a
 * hairline divider. Spacing (16 dp gutters, 8 dp gaps) and the `bodyLarge` / `bodySmall` typography
 * follow the shared kit (`ui/settings/SettingsSection.kt`); the legacy `AccentPanel` / `AppTokens`
 * kit is no longer used here. All fixed UI copy is localized via `stringResource`.
 *
 * Note (follow-up): the metadata/labels produced by the Android-free helpers
 * [recordingTitle], [recordingMetaLine], [statusLabel] (Russian-only, no `Context`) and the
 * `state.message` text built inside the ViewModels are not localized here.
 *
 * Honest states are explicit: loading, empty, «нет доступа к аудио» and «модель не скачана» each say
 * what is wrong and offer the Settings screen instead of faking content. Deletion is destructive, so
 * it is gated behind an [AlertDialog].
 *
 * References (official docs):
 * - Compose Material 3 `Scaffold`/`Surface`/`AlertDialog` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - Compose lists — https://developer.android.com/develop/ui/compose/lists
 * - `stringResource` (with format args) —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources ,
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/res/package-summary
 * - Activity Result `OpenDocument` (SAF audio picker) —
 *   https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument
 * - `rememberLauncherForActivityResult` —
 *   https://developer.android.com/reference/kotlin/androidx/activity/compose/rememberLauncherForActivityResult
 * - `hiltViewModel()` / `collectAsStateWithLifecycle()` —
 *   https://developer.android.com/training/dependency-injection/hilt-jetpack ,
 *   https://developer.android.com/reference/kotlin/androidx/lifecycle/compose/package-summary
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunicationsScreen(
    onBack: () -> Unit,
    onOpenTranscript: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CommunicationsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Подтверждение удаления: единственное разрушительное действие строки.
    var pendingDelete by remember { mutableStateOf<Recording?>(null) }

    // Аудио-пикер SAF: MIME `audio/*`. null (пользователь отменил) ничего не меняет.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::import)
    }

    val rowTracker = remember { EnterAnimationTracker() }


    Scaffold(
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.home_row_comms),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            SettingsSection(
                title = stringResource(R.string.comm_section_about_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .enterAnimation(index = 0)
            ) {
                SettingsHelperText(stringResource(R.string.comm_intro))
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.comm_search_label)) },
                supportingText = {
                    Text(stringResource(R.string.comm_search_support))
                },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.comm_search_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = searchFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .enterAnimation(index = 1, tracker = rowTracker, itemKey = "comms_search")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.comm_import_audio))
                }
                // Честное напоминание и путь к исправлению, когда модель ещё не скачана.
                if (!state.modelDownloaded) {
                    TextButton(onClick = onOpenSettings) {
                        Text(
                            text = stringResource(R.string.comm_model_not_downloaded),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val items = state.visibleItems
                when {
                    state.isLoading && state.recordings.isEmpty() ->
                        CenterMessage(stringResource(R.string.comm_loading))

                    state.isSearchActive && state.isSearching && items.isEmpty() ->
                        CenterMessage(stringResource(R.string.comm_searching))

                    items.isEmpty() -> when {
                        state.isSearchActive ->
                            CenterMessage(stringResource(R.string.comm_empty_search))

                        !state.audioPermissionGranted -> EmptyState(
                            text = stringResource(R.string.comm_no_audio_permission),
                            actionLabel = stringResource(R.string.comm_open_settings),
                            onAction = onOpenSettings
                        )

                        !state.modelDownloaded -> EmptyState(
                            text = stringResource(R.string.comm_empty_no_model),
                            actionLabel = stringResource(R.string.comm_open_settings),
                            onAction = onOpenSettings
                        )

                        else -> CenterMessage(stringResource(R.string.comm_empty))
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = items,
                            key = { _, item -> item.recording.id }
                        ) { index, item ->
                            RecordingRow(
                                item = item,
                                busy = item.recording.id in state.busyRecordingIds,
                                onOpen = { onOpenTranscript(item.recording.id) },
                                onTranscribe = { viewModel.transcribe(item.recording.id) },
                                onToggleKeep = { viewModel.toggleKeep(item.recording) },
                                onDelete = { pendingDelete = item.recording },
                                modifier = Modifier.enterAnimation(
                                    index = index + 2,
                                    tracker = rowTracker,
                                    itemKey = item.recording.id
                                )
                            )
                        }
                    }
                }
            }

            // Честная строка ошибки: появляется только когда последнее действие не удалось.
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }

    pendingDelete?.let { recording ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.comm_delete_recording)) },
            text = {
                Text(
                    stringResource(R.string.comm_delete_dialog_text, recordingTitle(recording))
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(recording)
                }) {
                    Text(
                        text = stringResource(R.string.settings_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}


/** Centered honest state with an optional action button (used for the empty/blocked variants). */
@Composable
private fun BoxScope.EmptyState(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * One recording as a rounded card: a tappable title + metadata block (with the matching transcript
 * snippet when the row came from a search), then its actions — transcribe, toggle the keep flag,
 * delete. The card surface (`shapes.large`, `surfaceContainerLow`) matches the unified style kit.
 */
@Composable
private fun RecordingRow(
    item: CommunicationsItem,
    busy: Boolean,
    onOpen: () -> Unit,
    onTranscribe: () -> Unit,
    onToggleKeep: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recording = item.recording
    val deleteLabel = stringResource(R.string.comm_delete_recording)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpen),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = recordingTitle(recording),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = recordingMetaLine(recording),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                item.snippet?.let { snippet ->
                    Text(
                        text = snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StatusBadge(status = recording.transcriptStatus)
                Spacer(modifier = Modifier.weight(1f))

                if (recording.transcriptStatus != TranscriptStatus.DONE) {
                    TextButton(onClick = onTranscribe, enabled = !busy) {
                        Text(
                            stringResource(
                                if (busy) R.string.comm_transcribing else R.string.comm_transcribe
                            )
                        )
                    }
                }
                TextButton(onClick = onToggleKeep) {
                    Text(
                        text = stringResource(
                            if (recording.keepFlag) R.string.comm_kept else R.string.comm_keep
                        ),
                        color = if (recording.keepFlag) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = deleteLabel }
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** Compact colored label for the recording's transcript lifecycle. */
@Composable
private fun StatusBadge(status: TranscriptStatus) {
    val color = when (status) {
        TranscriptStatus.DONE -> MaterialTheme.colorScheme.primary
        TranscriptStatus.FAILED -> MaterialTheme.colorScheme.error
        TranscriptStatus.RUNNING -> MaterialTheme.colorScheme.secondary
        TranscriptStatus.PENDING, TranscriptStatus.SKIPPED ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = statusLabel(status),
        style = MaterialTheme.typography.labelMedium,
        color = color
    )
}
