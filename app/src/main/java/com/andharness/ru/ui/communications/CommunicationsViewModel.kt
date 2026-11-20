package com.andharness.ru.ui.communications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.data.communications.CommunicationsConfigStore
import com.andharness.ru.data.communications.RecordingImporter
import com.andharness.ru.data.communications.TranscriptionService
import com.andharness.ru.data.communications.speech.SpeechModelStore
import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.communications.speech.SpeechModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the «Связи» screen: a bounded newest-first list of [Recording]s with three honest actions
 * (transcribe, toggle the keep flag, delete), an import action, and a debounced transcript search.
 *
 * It calls the Communications collaborators directly (no tool/handler round-trip): the list comes
 * from [RecordingsRepository], search from [TranscriptRepository], transcription from
 * [TranscriptionService], import from [RecordingImporter], and the speech-model state from
 * [SpeechModelStore]. Search reuses the same FTS index (`transcript_search`) the agent uses, so the
 * screen and the agent find the same moments.
 *
 * Everything is bounded: [LIST_LIMIT] recordings are loaded and [SEARCH_LIMIT] FTS candidates are
 * resolved, so neither the list nor a search can walk an unbounded database.
 */
@HiltViewModel
class CommunicationsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingsRepository: RecordingsRepository,
    private val transcriptRepository: TranscriptRepository,
    private val transcriptionService: TranscriptionService,
    private val recordingImporter: RecordingImporter,
    private val speechModelStore: SpeechModelStore,
    private val configStore: CommunicationsConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommunicationsUiState())
    val uiState: StateFlow<CommunicationsUiState> = _uiState.asStateFlow()

    /** In-flight debounced search; cancelled whenever the query changes. */
    private var searchJob: Job? = null

    init {
        load(clearMessage = true)
    }

    /** Reloads the recordings list and the model/permission flags; clears any stale message. */
    fun refresh() = load(clearMessage = true)

    private fun load(clearMessage: Boolean) {
        _uiState.update {
            it.copy(isLoading = true, message = if (clearMessage) null else it.message)
        }
        viewModelScope.launch {
            runCatching { recordingsRepository.list(LIST_LIMIT) }
                .onSuccess { recordings ->
                    _uiState.update {
                        it.copy(
                            recordings = recordings,
                            isLoading = false,
                            modelDownloaded = isModelDownloaded(currentModel()),
                            audioPermissionGranted = hasAudioPermission(),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = failureText("Не удалось прочитать записи", error),
                        )
                    }
                }
        }
    }

    /**
     * Updates the query and, after a short debounce, runs the transcript FTS search. A blank query
     * clears the hits and shows the full list again without touching the index.
     */
    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query, message = null) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            // Сбрасываем прошлые hits, чтобы новый запрос не показывал устаревшие строки.
            _uiState.update { it.copy(results = emptyList(), isSearching = true) }
            delay(SEARCH_DEBOUNCE_MS)
            runCatching { searchRecordings(query) }
                .onSuccess { items ->
                    _uiState.update { it.copy(results = items, isSearching = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            message = failureText("Не удалось выполнить поиск", error),
                        )
                    }
                }
        }
    }

    /**
     * Runs the lexical transcript search and resolves the matching recordings. FTS returns segment
     * ids; their segments give the recording ids and the first matching line as a snippet. Rows are
     * ordered newest-first, matching the browse list.
     */
    private suspend fun searchRecordings(query: String): List<CommunicationsItem> {
        val ids = transcriptRepository.searchFts(query, SEARCH_LIMIT)
        if (ids.isEmpty()) return emptyList()
        val segments = transcriptRepository.segmentsByIds(ids)
        if (segments.isEmpty()) return emptyList()
        val items = ArrayList<CommunicationsItem>(segments.size)
        for ((recordingId, group) in segments.groupBy { it.recordingId }) {
            val recording = recordingsRepository.getById(recordingId) ?: continue
            val snippet = group.minByOrNull { it.startMs }?.text
            items += CommunicationsItem(recording = recording, snippet = snippet)
        }
        return items.sortedByDescending { it.recording.createdAt }
    }

    /**
     * Transcribes one recording with the configured model/language. The speech model is never
     * downloaded here: a missing model is reported honestly and the user is pointed at Settings.
     */
    fun transcribe(recordingId: Long) {
        if (recordingId in _uiState.value.busyRecordingIds) return
        val model = currentModel()
        if (!isModelDownloaded(model)) {
            _uiState.update {
                it.copy(
                    modelDownloaded = false,
                    message = "Для расшифровки сначала скачайте модель в настройках.",
                )
            }
            return
        }
        _uiState.update {
            it.copy(busyRecordingIds = it.busyRecordingIds + recordingId, message = null)
        }
        viewModelScope.launch {
            val result = runCatching {
                transcriptionService.transcribeRecording(
                    recordingId = recordingId,
                    model = model,
                    language = configStore.currentWhisperLanguage(),
                )
            }
            result.fold(
                onSuccess = { outcome ->
                    _uiState.update {
                        it.copy(busyRecordingIds = it.busyRecordingIds - recordingId)
                    }
                    outcome.fold(
                        onSuccess = { done ->
                            _uiState.update {
                                it.copy(message = "Расшифровка готова: сегментов ${done.segmentCount}.")
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(message = failureText("Не удалось расшифровать", error))
                            }
                        },
                    )
                    // Перечитываем строку: сервис сам сохранил DONE/FAILED, UI показывает реальный статус.
                    load(clearMessage = false)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            busyRecordingIds = it.busyRecordingIds - recordingId,
                            message = failureText("Не удалось расшифровать", error),
                        )
                    }
                },
            )
        }
    }

    /** Flips the "keep regardless of retention" flag on one recording. */
    fun toggleKeep(recording: Recording) {
        val target = !recording.keepFlag
        viewModelScope.launch {
            runCatching { recordingsRepository.setKeepFlag(recording.id, target) }
                .onSuccess { changed ->
                    if (changed > 0) {
                        _uiState.update { state ->
                            state.copy(
                                recordings = state.recordings.map {
                                    if (it.id == recording.id) it.copy(keepFlag = target) else it
                                },
                                message = null,
                            )
                        }
                    } else {
                        _uiState.update { it.copy(message = "Запись #${recording.id} не найдена.") }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(message = failureText("Не удалось изменить флаг", error))
                    }
                }
        }
    }

    /** Deletes a recording and its transcript segments. */
    fun delete(recording: Recording) {
        val id = recording.id
        viewModelScope.launch {
            runCatching {
                transcriptRepository.deleteFor(id)
                recordingsRepository.delete(id)
            }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            recordings = state.recordings.filterNot { it.id == id },
                            results = state.results.filterNot { it.recording.id == id },
                            message = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(message = failureText("Не удалось удалить запись", error))
                    }
                }
        }
    }

    /**
     * Imports the picked audio through [RecordingImporter] and transcribes it in the same call.
     * A missing model still registers the row (marked `FAILED` by the pipeline) and the honest
     * reason is shown; the list is reloaded either way.
     */
    fun import(uri: Uri) {
        _uiState.update { it.copy(message = "Импорт…") }
        viewModelScope.launch {
            val result = runCatching {
                recordingImporter.import(
                    pathOrUri = uri.toString(),
                    transcribe = true,
                    language = configStore.currentWhisperLanguage(),
                )
            }
            result.fold(
                onSuccess = { outcome ->
                    outcome.fold(
                        onSuccess = { done ->
                            val name = done.displayName?.takeIf { it.isNotBlank() }
                            _uiState.update {
                                it.copy(
                                    message = if (name != null) {
                                        "Импортировано: $name"
                                    } else {
                                        "Запись импортирована."
                                    }
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(message = failureText("Не удалось импортировать", error))
                            }
                        },
                    )
                    load(clearMessage = false)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(message = failureText("Не удалось импортировать", error))
                    }
                },
            )
        }
    }

    /** The whisper model the pipeline will use, resolved from the persisted configuration. */
    private fun currentModel(): SpeechModel =
        SpeechModel.fromId(configStore.currentWhisperModelId()) ?: SpeechModel.DEFAULT

    private fun isModelDownloaded(model: SpeechModel): Boolean =
        runCatching { speechModelStore.isDownloaded(model) }.getOrDefault(false)

    /**
     * Whether the app may read the device's audio library. On Android 13+ this is
     * `READ_MEDIA_AUDIO`; on older releases the legacy storage model applies and the flag is treated
     * as granted, so the screen does not report a permission that does not exist there.
     *
     * https://developer.android.com/training/data-storage/shared/media#media_permissions
     */
    private fun hasAudioPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** One consistent «не удалось …: причина» line for every failure branch above. */
    private fun failureText(prefix: String, error: Throwable): String =
        "$prefix: ${error.message ?: error::class.simpleName}"

    private companion object {
        /** Page of recordings the screen holds, so the list read stays bounded. */
        const val LIST_LIMIT = 200

        /** FTS candidate ids resolved per search, bounding the follow-up segment read. */
        const val SEARCH_LIMIT = 50

        /** Quiet window before a keystroke turns into an FTS query. */
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
