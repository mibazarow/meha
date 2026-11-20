package com.andharness.ru.ui.communications

import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.RecordingSourceType
import com.andharness.ru.domain.communications.TranscriptStatus
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CommunicationsFormatTest {

    @Test
    fun clockFormatsMinutesAndHours() {
        assertEquals("0:00", formatClock(0L))
        assertEquals("0:07", formatClock(7_000L))
        assertEquals("1:05", formatClock(65_000L))
        assertEquals("59:59", formatClock(3_599_000L))
        assertEquals("1:00:00", formatClock(3_600_000L))
        assertEquals("1:01:01", formatClock(3_661_000L))
    }

    @Test
    fun negativeClockClampsToZero() {
        assertEquals("0:00", formatClock(-1_000L))
    }

    @Test
    fun timestampIsBracketedClock() {
        assertEquals("[0:03]", timestampLabel(3_000L))
        assertEquals("[1:02:03]", timestampLabel(3_723_000L))
    }

    @Test
    fun durationReusesTheClockFormat() {
        assertEquals("3:42", durationLabel(222_000L))
        assertEquals("0:00", durationLabel(0L))
    }

    @Test
    fun everyStatusHasAnHonestRussianLabel() {
        assertEquals("Ожидает", statusLabel(TranscriptStatus.PENDING))
        assertEquals("Расшифровка…", statusLabel(TranscriptStatus.RUNNING))
        assertEquals("Готово", statusLabel(TranscriptStatus.DONE))
        assertEquals("Ошибка", statusLabel(TranscriptStatus.FAILED))
        assertEquals("Пропущено", statusLabel(TranscriptStatus.SKIPPED))
    }

    @Test
    fun everySourceHasAnHonestRussianLabel() {
        assertEquals("Диктофон", sourceLabel(RecordingSourceType.DICTAPHONE))
        assertEquals("Запись", sourceLabel(RecordingSourceType.MANUAL))
        assertEquals("Голосовое", sourceLabel(RecordingSourceType.MESSENGER_VOICE))
        assertEquals("Импорт", sourceLabel(RecordingSourceType.IMPORTED))
    }

    @Test
    fun metaLineJoinsDateDurationAndSourceInAGivenZone() {
        val recording = recording(
            createdAt = 1_700_000_000_000L, // 2023-11-14T22:13:20Z
            durationMs = 222_000L,
            sourceType = RecordingSourceType.IMPORTED,
        )
        assertEquals(
            "14.11.2023 22:13 · 3:42 · Импорт",
            recordingMetaLine(recording, ZoneId.of("UTC")),
        )
    }

    @Test
    fun titlePrefersTheDisplayName() {
        assertEquals("Позвонок", recordingTitle(recording(displayName = "Позвонок")))
        assertEquals("Позвонок", recordingTitle(recording(displayName = "  Позвонок  ")))
    }

    @Test
    fun titleFallsBackToTheFileNameForPlainPaths() {
        val recording = recording(filePath = "/storage/emulated/0/Recordings/call.m4a")
        assertEquals("call.m4a", recordingTitle(recording))
    }

    @Test
    fun titleFallsBackToTheIdForContentUrisAndBlankPaths() {
        assertEquals(
            "Запись #5",
            recordingTitle(recording(id = 5L, filePath = "content://media/external/audio/media/123")),
        )
        assertEquals(
            "Запись #5",
            recordingTitle(recording(id = 5L, filePath = "   ")),
        )
    }

    @Test
    fun selfSpeakerIsAlwaysYa() {
        assertEquals("Я", speakerLabel(isSelf = true, name = "Аня", personName = "Иван", fallbackIndex = 3))
    }

    @Test
    fun linkedPersonNameWinsOverTheSpeakerName() {
        assertEquals(
            "Иван",
            speakerLabel(isSelf = false, name = "Аня", personName = "Иван", fallbackIndex = 3),
        )
    }

    @Test
    fun speakerNameIsUsedWhenThereIsNoPerson() {
        assertEquals(
            "Аня",
            speakerLabel(isSelf = false, name = "Аня", personName = null, fallbackIndex = 3),
        )
    }

    @Test
    fun unnamedSpeakerFallsBackToTheIndexThenTheGenericLabel() {
        assertEquals(
            "Говорящий 2",
            speakerLabel(isSelf = false, name = "  ", personName = null, fallbackIndex = 2),
        )
        assertEquals(
            "Говорящий",
            speakerLabel(isSelf = false, name = null, personName = null, fallbackIndex = null),
        )
    }

    private fun recording(
        id: Long = 1L,
        sourceType: RecordingSourceType = RecordingSourceType.DICTAPHONE,
        filePath: String = "/tmp/audio.m4a",
        displayName: String? = null,
        durationMs: Long = 0L,
        createdAt: Long = 0L,
    ) = Recording(
        id = id,
        sourceType = sourceType,
        filePath = filePath,
        displayName = displayName,
        durationMs = durationMs,
        sizeBytes = 0L,
        sourcePackage = null,
        transcriptStatus = TranscriptStatus.PENDING,
        keepFlag = false,
        personId = null,
        errorText = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
