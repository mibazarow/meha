package com.andharness.ru.ui.communications

import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.Speaker
import com.andharness.ru.domain.communications.TranscriptSegment
import com.andharness.ru.domain.person.Person

/**
 * State of the transcript viewer for one recording.
 *
 * [segments] is the recording's transcript in playback order; [speakers] and [persons] are the
 * registries used to turn a [TranscriptSegment.speakerId] into a human label. [modelDownloaded]
 * decides whether the «нет расшифровки» state can offer a transcription at all.
 *
 * [message] carries an honest failure/status line only.
 */
data class TranscriptUiState(
    val recording: Recording? = null,
    val segments: List<TranscriptSegment> = emptyList(),
    val speakers: List<Speaker> = emptyList(),
    val persons: List<Person> = emptyList(),
    val isLoading: Boolean = true,
    val isTranscribing: Boolean = false,
    val modelDownloaded: Boolean = false,
    val message: String? = null,
) {
    /** True once the recording has at least one transcript segment. */
    val hasTranscript: Boolean get() = segments.isNotEmpty()

    /** The registered [Speaker] of a segment, or `null` when it is unattributed/unknown. */
    fun speakerOf(speakerId: Long?): Speaker? =
        speakerId?.let { id -> speakers.firstOrNull { it.id == id } }

    /**
     * Human label for a segment's speaker, or `null` when the segment has no known speaker (so the
     * row can simply omit the label). Resolves «Я» / linked person name / speaker name / «Говорящий N».
     */
    fun speakerLabelFor(segment: TranscriptSegment): String? {
        val speaker = speakerOf(segment.speakerId) ?: return null
        val personName = speaker.personId
            ?.let { personId -> persons.firstOrNull { it.id == personId }?.displayName }
        val index = speakers.indexOfFirst { it.id == speaker.id }
            .takeIf { it >= 0 }
            ?.plus(1)
        return speakerLabel(
            isSelf = speaker.isSelf,
            name = speaker.name,
            personName = personName,
            fallbackIndex = index,
        )
    }
}
