package com.andharness.ru.data.communications.speech

/**
 * One timestamped transcription segment produced by the bundled `whisper-cli`.
 *
 * [startMs]/[endMs] are absolute offsets into the decoded 16 kHz mono WAV, in **milliseconds**,
 * taken from whisper.cpp's `offsets` field (which is already in ms: `t * 10` where `t` is the
 * internal 10 ms tick). [text] is the segment's recognized text, already trimmed.
 */
data class WhisperSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
