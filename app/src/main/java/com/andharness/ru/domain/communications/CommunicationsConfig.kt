package com.andharness.ru.domain.communications

import com.andharness.ru.domain.communications.speech.SpeechModel
import com.andharness.ru.domain.communications.speech.SpeechModelKind

/**
 * Pure defaults and bounds for the persisted Communications settings. Kept free of Android imports
 * so the clamping rules are unit-testable on the JVM; the Android-facing
 * [com.andharness.ru.data.communications.CommunicationsConfigStore] only wraps a
 * [android.content.SharedPreferences] around these values.
 */
object CommunicationsConfig {

    val DEFAULT_MODE: CommunicationsMode = CommunicationsMode.DEFAULT

    /** How many days of source audio are kept before the retention pass may delete it. */
    const val DEFAULT_KEEP_AUDIO_DAYS: Int = 3
    const val MIN_KEEP_AUDIO_DAYS: Int = 1
    const val MAX_KEEP_AUDIO_DAYS: Int = 30

    /** Upper bound of the on-device audio cache, in MiB. */
    const val DEFAULT_AUDIO_CACHE_LIMIT_MB: Int = 500
    const val MIN_AUDIO_CACHE_LIMIT_MB: Int = 50
    const val MAX_AUDIO_CACHE_LIMIT_MB: Int = 5000

    /**
     * Wall-clock budget for background transcription, in minutes per calendar day. The background
     * worker stops consuming recordings once the day's used time reaches this value, so an on-device
     * whisper run can never burn the battery indefinitely.
     */
    const val DEFAULT_MAX_PROCESS_MINUTES_PER_DAY: Int = 120
    const val MIN_MAX_PROCESS_MINUTES_PER_DAY: Int = 15
    const val MAX_MAX_PROCESS_MINUTES_PER_DAY: Int = 1440

    /** Speech-recognition model the pipeline loads by default. */
    val DEFAULT_WHISPER_MODEL_ID: String = SpeechModel.DEFAULT.id

    /** `auto` lets whisper detect the spoken language, the multilingual default. */
    const val DEFAULT_WHISPER_LANGUAGE: String = "auto"

    /**
     * When `true` (default), transcript TEXT may be sent to the configured cloud LLM for summaries.
     * Source audio NEVER leaves the device in either case.
     */
    const val DEFAULT_SEND_TRANSCRIPTS_TO_CLOUD: Boolean = true

    /** Speaker diarization (who said what) is on by default. */
    const val DEFAULT_DIARIZATION_ENABLED: Boolean = true

    /** Extra folders to scan for recordings, empty by default. */
    val DEFAULT_RECORDINGS_FOLDERS: Set<String> = emptySet()

    fun clampKeepAudioDays(value: Int): Int =
        value.coerceIn(MIN_KEEP_AUDIO_DAYS, MAX_KEEP_AUDIO_DAYS)

    fun clampAudioCacheLimitMb(value: Int): Int =
        value.coerceIn(MIN_AUDIO_CACHE_LIMIT_MB, MAX_AUDIO_CACHE_LIMIT_MB)

    fun clampMaxProcessMinutesPerDay(value: Int): Int =
        value.coerceIn(MIN_MAX_PROCESS_MINUTES_PER_DAY, MAX_MAX_PROCESS_MINUTES_PER_DAY)

    /**
     * Normalizes a persisted whisper model token: a known [SpeechModel] id is kept as-is, anything
     * else (including `null`) falls back to [DEFAULT_WHISPER_MODEL_ID]. The result is always a valid
     * whisper (non-VAD) id, so the pipeline never resolves to the Silero VAD model by accident.
     */
    fun normalizedWhisperModelId(id: String?): String {
        val model = id?.let { SpeechModel.fromId(it) }
        return if (model != null && model.kind == SpeechModelKind.WHISPER) {
            model.id
        } else {
            DEFAULT_WHISPER_MODEL_ID
        }
    }
}
