package com.andharness.ru.ui.communications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.domain.communications.Speaker
import com.andharness.ru.domain.communications.TranscriptSegment
import com.andharness.ru.domain.communications.TranscriptStatus
import com.andharness.ru.domain.person.Person
import com.andharness.ru.ui.components.CenterMessage
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection

/**
 * Transcript viewer for one recording. The `recordingId` comes from the `transcript/{recordingId}`
 * route and is read by [TranscriptViewModel] through its `SavedStateHandle`.
 *
 * Content is re-skinned to the unified internal-page style matching the Settings/Memory redesign:
 * the recording header is a [SettingsSection] with muted [SettingsHelperText], and every segment is
 * a rounded [Surface] card (`shapes.large`, `surfaceContainerLow`) instead of a flat row behind a
 * hairline divider; gaps are 8 dp and gutters 16 dp. The legacy `AccentPanel` / `AppTokens` kit is
 * no longer used here. All fixed UI copy is localized via `stringResource`.
 *
 * It shows the recording's metadata, then each [TranscriptSegment] as `[mm:ss]` + speaker label +
 * text. Tapping a segment that has a speaker opens [SpeakerDialog] to rename the speaker, link it to
 * a person of the unified base, or mark it as the device owner («это я»). When there is no
 * transcript yet, an honest empty state offers the transcription action instead.
 *
 * Note (follow-up): [recordingTitle], [recordingMetaLine] and [statusLabel] are Android-free
 * Russian-only helpers without a `Context`, and the `state.message` text is built inside the
 * ViewModel; none of these are localized here.
 *
 * References (official docs):
 * - Compose Material 3 `Scaffold`/`Surface`/`AlertDialog` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - Compose lists — https://developer.android.com/develop/ui/compose/lists
 * - `stringResource` (with format args) —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources ,
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/res/package-summary
 * - `hiltViewModel()` / `collectAsStateWithLifecycle()` —
 *   https://developer.android.com/training/dependency-injection/hilt-jetpack ,
 *   https://developer.android.com/reference/kotlin/androidx/lifecycle/compose/package-summary
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(
    onBack: () -> Unit,
    onOpenPerson: (Long) -> Unit = {},
    viewModel: TranscriptViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Сегмент, для которого открыт диалог спикера (только сегменты с известным speakerId).
    var selectedSegment by remember { mutableStateOf<TranscriptSegment?>(null) }

    val rowTracker = remember { EnterAnimationTracker() }
    val title = state.recording?.let { recordingTitle(it) }
        ?: stringResource(R.string.transcript_title_fallback)

    Scaffold(
        topBar = {
            InternalTopBar(
                title = title,
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            SettingsSection(
                title = stringResource(R.string.transcript_recording_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .enterAnimation(index = 0)
            ) {
                val recording = state.recording
                if (recording != null) {
                    Text(
                        text = recordingMetaLine(recording),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = transcriptStatusLine(
                            recording.transcriptStatus,
                            recording.errorText
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(recording.transcriptStatus)
                    )
                } else if (!state.isLoading) {
                    Text(
                        text = stringResource(R.string.transcript_recording_not_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                SettingsHelperText(stringResource(R.string.transcript_local_hint))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    state.isLoading -> CenterMessage(stringResource(R.string.transcript_loading))

                    !state.hasTranscript -> EmptyTranscript(
                        isTranscribing = state.isTranscribing,
                        modelDownloaded = state.modelDownloaded,
                        onTranscribe = viewModel::transcribe
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = state.segments,
                            key = { _, segment -> segment.id }
                        ) { index, segment ->
                            SegmentRow(
                                segment = segment,
                                speaker = state.speakerLabelFor(segment),
                                onClick = { selectedSegment = segment },
                                modifier = Modifier.enterAnimation(
                                    index = index + 1,
                                    tracker = rowTracker,
                                    itemKey = segment.id
                                )
                            )
                        }
                    }
                }
            }

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

    val openSegment = selectedSegment
    val openSpeaker = state.speakerOf(openSegment?.speakerId)
    if (openSegment != null && openSpeaker != null) {
        SpeakerDialog(
            speaker = openSpeaker,
            persons = state.persons,
            onDismiss = { selectedSegment = null },
            onRename = { name ->
                viewModel.renameSpeaker(openSpeaker.id, name)
                selectedSegment = null
            },
            onLinkPerson = { personId ->
                viewModel.linkSpeakerToPerson(openSpeaker.id, personId)
                selectedSegment = null
            },
            onMarkSelf = {
                viewModel.markSpeakerAsSelf(openSpeaker)
                selectedSegment = null
            },
            onOpenPerson = { personId ->
                selectedSegment = null
                onOpenPerson(personId)
            }
        )
    }
}

/** Centered honest line for the loading/empty states. */

/** «Нет расшифровки» state: explains the situation and offers the transcription action. */
@Composable
private fun BoxScope.EmptyTranscript(
    isTranscribing: Boolean,
    modelDownloaded: Boolean,
    onTranscribe: () -> Unit
) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.transcript_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (!modelDownloaded) {
            Text(
                text = stringResource(R.string.transcript_model_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        OutlinedButton(onClick = onTranscribe, enabled = !isTranscribing) {
            Text(
                stringResource(
                    if (isTranscribing) R.string.comm_transcribing else R.string.comm_transcribe
                )
            )
        }
    }
}

/**
 * One transcript line as a rounded card: the `[mm:ss]` timestamp on the left, then the speaker label
 * (when known) and the recognised text. A segment is tappable only when it has a speaker to edit.
 */
@Composable
private fun SegmentRow(
    segment: TranscriptSegment,
    speaker: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = segment.speakerId != null, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = timestampLabel(segment.startMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (speaker != null) {
                    Text(
                        text = speaker,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

/**
 * Dialog for one speaker: rename, link to a person of the unified base, or mark as the device owner.
 * The person list is bounded by the ViewModel ([TranscriptViewModel] loads the recent ones), so the
 * dialog cannot become unbounded; it still scrolls for small screens.
 */
@Composable
private fun SpeakerDialog(
    speaker: Speaker,
    persons: List<Person>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onLinkPerson: (Long) -> Unit,
    onMarkSelf: () -> Unit,
    onOpenPerson: (Long) -> Unit
) {
    var nameInput by remember(speaker.id) { mutableStateOf(speaker.name.orEmpty()) }
    // Локальная копия: smart-cast свойства другого класса запрещён, а id нужен внутри лямбды.
    val linkedPersonId = speaker.personId

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transcript_speaker_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.transcript_speaker_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onRename(nameInput) },
                        enabled = nameInput.isNotBlank()
                    ) {
                        Text(stringResource(R.string.transcript_save_name))
                    }
                    OutlinedButton(
                        onClick = onMarkSelf,
                        enabled = !speaker.isSelf
                    ) {
                        Text(stringResource(R.string.transcript_mark_self))
                    }
                }

                if (linkedPersonId != null) {
                    OutlinedButton(
                        onClick = { onOpenPerson(linkedPersonId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.transcript_open_person))
                    }
                }

                Text(
                    text = stringResource(R.string.transcript_link_person_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (persons.isEmpty()) {
                    Text(
                        text = stringResource(R.string.transcript_no_persons),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    persons.forEach { person ->
                        TextButton(
                            onClick = { onLinkPerson(person.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(person.displayName)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.transcript_done))
            }
        }
    )
}

/** Status line for the recording header, including the persisted failure reason when present. */
@Composable
private fun transcriptStatusLine(status: TranscriptStatus, errorText: String?): String = when {
    status == TranscriptStatus.FAILED && !errorText.isNullOrBlank() ->
        stringResource(R.string.transcript_error_prefix, errorText)

    else -> stringResource(R.string.transcript_status_prefix, statusLabel(status))
}

@Composable
private fun statusColor(status: TranscriptStatus) = when (status) {
    TranscriptStatus.DONE -> MaterialTheme.colorScheme.primary
    TranscriptStatus.FAILED -> MaterialTheme.colorScheme.error
    TranscriptStatus.RUNNING -> MaterialTheme.colorScheme.secondary
    TranscriptStatus.PENDING, TranscriptStatus.SKIPPED ->
        MaterialTheme.colorScheme.onSurfaceVariant
}
