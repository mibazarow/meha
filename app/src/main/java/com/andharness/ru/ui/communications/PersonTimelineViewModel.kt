package com.andharness.ru.ui.communications

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.communications.PersonBriefComposer
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.SpeakerRepository
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.person.InteractionRepository
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
 * Backs one person's timeline. The `personId` comes from the `person/{personId}` route through
 * [SavedStateHandle] (same pattern as `TranscriptViewModel`'s `recordingId`).
 *
 * It merges three sources into one chronological list:
 * - interactions (calls/SMS/messenger) from [InteractionRepository.recentFor];
 * - transcript segments where this person is the speaker — resolved through the existing
 *   [SpeakerRepository] soft links (`Speaker.personId`) and read per recording from
 *   [TranscriptRepository.segmentsFor];
 * - linked memories/opinions from [AssistRepository] (lexical recall for memories, name mention for
 *   opinions).
 *
 * The same data feeds [PersonBriefComposer], so the «О человеке» header is exactly what the
 * `person_brief` tool would report. Every read is bounded ([INTERACTIONS_LIMIT], [RECORDINGS_LIMIT],
 * [MOMENTS_LIMIT], [MEMORY_LIMIT], [OPINION_LIMIT]).
 */
@HiltViewModel
class PersonTimelineViewModel @Inject constructor(
    private val personRepository: PersonRepository,
    private val interactionRepository: InteractionRepository,
    private val speakerRepository: SpeakerRepository,
    private val recordingsRepository: RecordingsRepository,
    private val transcriptRepository: TranscriptRepository,
    private val assistRepository: AssistRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val personId: Long = savedStateHandle["personId"] ?: 0L

    private val _uiState = MutableStateFlow(PersonTimelineUiState())
    val uiState: StateFlow<PersonTimelineUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** (Re)builds the whole timeline; clears any stale message. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true, message = null) }
        viewModelScope.launch {
            runCatching { build() }
                .onSuccess { loaded -> _uiState.value = loaded }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = failureText("Не удалось загрузить человека", error),
                        )
                    }
                }
        }
    }

    private suspend fun build(): PersonTimelineUiState {
        val person: Person = personRepository.get(personId)
            ?: return PersonTimelineUiState(
                person = null,
                isLoading = false,
                message = "Человек #$personId не найден.",
            )
        val now = System.currentTimeMillis()

        val interactions = interactionRepository.recentFor(personId, INTERACTIONS_LIMIT)
        val moments = collectMoments(person.id)

        // Воспоминания — только лексически совпавшие, чтобы не подмешивать чужие заметки.
        val memories = runCatching {
            assistRepository.searchMemoriesRanked(person.displayName, MEMORY_LIMIT)
                .filter { it.lexicallyMatched }
                .map { it.note }
        }.getOrDefault(emptyList())

        // Мнения — только те, где человек упомянут по имени или тегу.
        val opinions = runCatching {
            assistRepository.listOpinions(OPINION_LIMIT).filter { note ->
                person.displayName.isNotBlank() &&
                    (note.text.contains(person.displayName, ignoreCase = true) ||
                        note.tags.contains(person.displayName, ignoreCase = true))
            }
        }.getOrDefault(emptyList())

        val entries = buildList {
            interactions.forEach { add(interactionEntry(it)) }
            moments.forEach {
                add(
                    transcriptEntry(
                        segmentId = it.segmentId,
                        at = it.at,
                        recordingLabel = it.recordingLabel,
                        startMs = it.startMs,
                        endMs = it.endMs,
                        text = it.text,
                    )
                )
            }
            memories.forEach { add(noteEntry(PersonTimelineKind.MEMORY, it)) }
            opinions.forEach { add(noteEntry(PersonTimelineKind.OPINION, it)) }
        }

        val brief = PersonBriefComposer.render(
            personName = person.displayName,
            personId = person.id,
            days = BRIEF_DAYS,
            now = now,
            interactions = interactions.map { interaction ->
                PersonBriefComposer.InteractionLine(
                    at = interaction.at,
                    channel = interaction.channel,
                    direction = interaction.direction,
                    summary = listOfNotNull(
                        interaction.title?.takeIf { it.isNotBlank() },
                        interaction.summary?.takeIf { it.isNotBlank() },
                    ).joinToString(" — ").ifBlank { null },
                )
            },
            moments = moments.map { moment ->
                PersonBriefComposer.TranscriptMoment(
                    at = moment.at,
                    recordingLabel = moment.recordingLabel,
                    startMs = moment.startMs,
                    endMs = moment.endMs,
                    text = moment.text,
                )
            },
            memories = memories.map { PersonBriefComposer.DatedText(it.createdAt, it.text) },
            opinions = opinions.map { PersonBriefComposer.DatedText(it.createdAt, it.text) },
        )

        return PersonTimelineUiState(
            person = person,
            brief = brief,
            sections = PersonTimelineComposer.compose(entries),
            isLoading = false,
        )
    }

    /**
     * Transcript moments where this person speaks. The person's speakers are found through the
     * existing [SpeakerRepository] links; their segments are then read per (bounded) recording.
     * Recordings are scanned newest-first and the scan stops at [MOMENTS_LIMIT] moments, so the read
     * stays bounded even with a large library.
     */
    private suspend fun collectMoments(personId: Long): List<Moment> {
        val speakerIds = speakerRepository.list()
            .filter { it.personId == personId }
            .map { it.id }
            .toSet()
        if (speakerIds.isEmpty()) return emptyList()

        val out = ArrayList<Moment>()
        for (recording in recordingsRepository.list(RECORDINGS_LIMIT)) {
            val label = recording.displayName?.takeIf { it.isNotBlank() }
                ?: "Запись #${recording.id}"
            for (segment in transcriptRepository.segmentsFor(recording.id)) {
                val speakerId = segment.speakerId ?: continue
                if (speakerId !in speakerIds) continue
                out += Moment(
                    segmentId = segment.id,
                    at = recording.createdAt,
                    recordingLabel = label,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    text = segment.text,
                )
                if (out.size >= MOMENTS_LIMIT) return out
            }
        }
        return out
    }

    /** One transcript moment, carried between the timeline entries and the brief composer. */
    private data class Moment(
        val segmentId: Long,
        val at: Long,
        val recordingLabel: String,
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    private fun failureText(prefix: String, error: Throwable): String =
        "$prefix: ${error.message ?: error::class.simpleName}"

    private companion object {
        /** Days covered by the «О человеке» brief (matches the `person_brief` default). */
        const val BRIEF_DAYS = 30

        /** Newest interactions loaded for the timeline and the brief. */
        const val INTERACTIONS_LIMIT = 60

        /** Newest recordings scanned for the person's speech. */
        const val RECORDINGS_LIMIT = 80

        /** Hard cap on transcript moments included. */
        const val MOMENTS_LIMIT = 200

        /** Memories pulled by lexical recall for the brief/timeline. */
        const val MEMORY_LIMIT = 30

        /** Opinions scanned for a mention of the person. */
        const val OPINION_LIMIT = 50
    }
}
