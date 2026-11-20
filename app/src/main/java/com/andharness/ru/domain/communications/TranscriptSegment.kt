package com.andharness.ru.domain.communications

/**
 * One timestamped utterance of a [Recording]'s transcript.
 *
 * Segments are the unit the UI highlights and the unit the dense-vector store embeds; they are
 * deleted together with their recording. [speakerId] stays `null` until diarisation attributes the
 * utterance; [confidence] is the recogniser's score in `[0, 1]`, when the engine reports one.
 *
 * Android-free on purpose so it is unit-testable on the JVM.
 */
data class TranscriptSegment(
    val id: Long,
    val recordingId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerId: Long?,
    val confidence: Float?,
    val createdAt: Long
)
