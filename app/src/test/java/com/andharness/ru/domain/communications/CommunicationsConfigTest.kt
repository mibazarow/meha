package com.andharness.ru.domain.communications

import com.andharness.ru.domain.communications.speech.SpeechModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the Communications defaults and the clamp/normalization helpers the Android-facing
 * `CommunicationsConfigStore` delegates to. No Android or `SharedPreferences` involved.
 */
class CommunicationsConfigTest {

    @Test
    fun defaultsMatchTheSpecifiedValues() {
        assertEquals(CommunicationsMode.CHARGING, CommunicationsConfig.DEFAULT_MODE)
        assertEquals(3, CommunicationsConfig.DEFAULT_KEEP_AUDIO_DAYS)
        assertEquals(500, CommunicationsConfig.DEFAULT_AUDIO_CACHE_LIMIT_MB)
        assertEquals(120, CommunicationsConfig.DEFAULT_MAX_PROCESS_MINUTES_PER_DAY)
        assertEquals(SpeechModel.DEFAULT.id, CommunicationsConfig.DEFAULT_WHISPER_MODEL_ID)
        assertEquals("auto", CommunicationsConfig.DEFAULT_WHISPER_LANGUAGE)
        assertTrue(CommunicationsConfig.DEFAULT_SEND_TRANSCRIPTS_TO_CLOUD)
        assertTrue(CommunicationsConfig.DEFAULT_DIARIZATION_ENABLED)
        assertTrue(CommunicationsConfig.DEFAULT_RECORDINGS_FOLDERS.isEmpty())
    }

    @Test
    fun keepAudioDaysIsClampedTo1Through30() {
        assertEquals(1, CommunicationsConfig.clampKeepAudioDays(0))
        assertEquals(1, CommunicationsConfig.clampKeepAudioDays(-5))
        assertEquals(3, CommunicationsConfig.clampKeepAudioDays(3))
        assertEquals(30, CommunicationsConfig.clampKeepAudioDays(31))
        assertEquals(30, CommunicationsConfig.clampKeepAudioDays(999))
    }

    @Test
    fun audioCacheLimitIsClampedTo50Through5000() {
        assertEquals(50, CommunicationsConfig.clampAudioCacheLimitMb(0))
        assertEquals(50, CommunicationsConfig.clampAudioCacheLimitMb(49))
        assertEquals(500, CommunicationsConfig.clampAudioCacheLimitMb(500))
        assertEquals(5000, CommunicationsConfig.clampAudioCacheLimitMb(5001))
        assertEquals(5000, CommunicationsConfig.clampAudioCacheLimitMb(100_000))
    }

    @Test
    fun maxProcessMinutesPerDayIsClampedTo15Through1440() {
        assertEquals(15, CommunicationsConfig.clampMaxProcessMinutesPerDay(0))
        assertEquals(15, CommunicationsConfig.clampMaxProcessMinutesPerDay(14))
        assertEquals(120, CommunicationsConfig.clampMaxProcessMinutesPerDay(120))
        assertEquals(1440, CommunicationsConfig.clampMaxProcessMinutesPerDay(1441))
        assertEquals(1440, CommunicationsConfig.clampMaxProcessMinutesPerDay(100_000))
    }

    @Test
    fun normalizedWhisperModelIdKeepsWhisperModels() {
        assertEquals(
            SpeechModel.WHISPER_BASE.id,
            CommunicationsConfig.normalizedWhisperModelId(SpeechModel.WHISPER_BASE.id),
        )
        assertEquals(
            SpeechModel.WHISPER_SMALL.id,
            CommunicationsConfig.normalizedWhisperModelId(SpeechModel.WHISPER_SMALL.id),
        )
    }

    @Test
    fun normalizedWhisperModelIdFallsBackForNullUnknownAndVad() {
        assertEquals(
            CommunicationsConfig.DEFAULT_WHISPER_MODEL_ID,
            CommunicationsConfig.normalizedWhisperModelId(null),
        )
        assertEquals(
            CommunicationsConfig.DEFAULT_WHISPER_MODEL_ID,
            CommunicationsConfig.normalizedWhisperModelId("does_not_exist"),
        )
        assertEquals(
            CommunicationsConfig.DEFAULT_WHISPER_MODEL_ID,
            CommunicationsConfig.normalizedWhisperModelId(SpeechModel.VAD_SILERO.id),
        )
    }
}
