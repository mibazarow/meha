package com.andharness.ru.ui.communications

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.data.communications.CommunicationsConfigStore
import com.andharness.ru.data.communications.TranscriptionService
import com.andharness.ru.data.communications.speech.SpeechModelStore
import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.Speaker
import com.andharness.ru.domain.communications.SpeakerRepository
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.communications.TranscriptSegment
import com.andharness.ru.domain.communications.speech.SpeechModel
import com.andharness.ru.domain.person.Person
import com.andharness.ru.domain.person.PersonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the transcript viewer for one recording. The `recordingId` comes from the navigation route
 * through [SavedStateHandle] (same pattern as `ChatViewModel`'s `chatId`).
 *
 * It loads the recording, its segments, the speaker registry and the recent people, then offers:
 * transcribe (when there is no transcript), rename a speaker, mark a speaker as the device owner,
 * and link a speaker to a person. All writes go straight to the Communications / person
 * repositories — no tool handler round-trip.
 */
@HiltViewModel
class TranscriptViewModel @Inject constructor(
    private val recordingsRepository: RecordingsRepository,
    private val transcriptRepository: TranscriptRepository,
    private val speakerRepository: SpeakerRepository,
    private val personRepository: PersonRepository,
    private val transcriptionService: TranscriptionService,
    private val speechModelStore: SpeechModelStore,
    private val configStore: CommunicationsConfigStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val recordingId: Long = savedStateHandle["recordingId"] ?: 0L

    private val _uiState = MutableStateFlow(TranscriptUiState())
    val uiState: StateFlow<TranscriptUiState> = _uiState.asStateFlow()

    init {
        load(clearMessage = true)
    }

    private fun load(clearMessage: Boolean) {
        _uiState.update {
            it.copy(isLoading = true, message = if (clearMessage) null else it.message)
        }
        viewModelScope.launch {
            runCatching {
                Loaded(
                    recording = recordingsRepository.getById(recordingId),
                    segments = transcriptRepository.segmentsFor(recordingId),
                    speakers = speakerRepository.list(),
                    persons = personRepository.recent(PERSON_LIMIT),
                )
            }
                .onSuccess { loaded ->
                    _uiState.update {
                        it.copy(
                            recording = loaded.recording,
                            segments = loaded.segments,
                            speakers = loaded.speakers,
                            persons = loaded.persons,
                            isLoading = false,
                            modelDownloaded = isModelDownloaded(currentModel()),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = failureText("Не удалось загрузить расшифровку", error),
                        )
                    }
                }
        }
    }

    /**
     * Transcribes this recording with the configured model/language. A missing speech model is
     * reported honestly; it is never downloaded implicitly.
     */
    fun transcribe() {
        if (_uiState.value.isTranscribing) return
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
        _uiState.update { it.copy(isTranscribing = true, message = null) }
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
                    outcome.fold(
                        onSuccess = { done ->
                            _uiState.update {
                                it.copy(
                                    isTranscribing = false,
                                    message = "Расшифровка готова: сегментов ${done.segmentCount}.",
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    isTranscribing = false,
                                    message = failureText("Не удалось расшифровать", error),
                                )
                            }
                        },
                    )
                    load(clearMessage = false)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isTranscribing = false,
                            message = failureText("Не удалось расшифровать", error),
                        )
                    }
                },
            )
        }
    }

    /** Renames a speaker (blank clears the name). */
    fun renameSpeaker(speakerId: Long, name: String) {
        viewModelScope.launch {
            runCatching {
                speakerRepository.setName(speakerId, name.trim().takeIf { it.isNotEmpty() })
            }
                .onSuccess { load(clearMessage = true) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(message = failureText("Не удалось переименовать", error))
                    }
                }
        }
    }

    /** Links a speaker to a person of the unified base. */
    fun linkSpeakerToPerson(speakerId: Long, personId: Long) {
        viewModelScope.launch {
            runCatching { speakerRepository.linkToPerson(speakerId, personId) }
                .onSuccess { load(clearMessage = true) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(message = failureText("Не удалось привязать к человеку", error))
                    }
                }
        }
    }

    /** Marks a speaker as the device owner's voice («это я»). */
    fun markSpeakerAsSelf(speaker: Speaker) {
        if (speaker.isSelf) return
        viewModelScope.launch {
            runCatching { speakerRepository.upsert(speaker.copy(isSelf = true)) }
                .onSuccess { load(clearMessage = true) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(message = failureText("Не удалось отметить говорящего", error))
                    }
                }
        }
    }

    private fun currentModel(): SpeechModel =
        SpeechModel.fromId(configStore.currentWhisperModelId()) ?: SpeechModel.DEFAULT

    private fun isModelDownloaded(model: SpeechModel): Boolean =
        runCatching { speechModelStore.isDownloaded(model) }.getOrDefault(false)

    private fun failureText(prefix: String, error: Throwable): String =
        "$prefix: ${error.message ?: error::class.simpleName}"

    /** Everything the viewer needs for one render, read in a single coroutine. */
    private data class Loaded(
        val recording: Recording?,
        val segments: List<TranscriptSegment>,
        val speakers: List<Speaker>,
        val persons: List<Person>,
    )

    private companion object {
        /** Recent people offered for linking a speaker; keeps the dialog bounded. */
        const val PERSON_LIMIT = 20
    }
}
