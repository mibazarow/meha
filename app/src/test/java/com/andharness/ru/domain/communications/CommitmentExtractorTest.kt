package com.andharness.ru.domain.communications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [CommitmentExtractor] heuristic: cue matching, de-duplication, bounding and
 * the window parser. No Android dependency, so the "today" boundary is deterministic here.
 */
class CommitmentExtractorTest {

    private fun segment(
        id: Long,
        text: String,
        recordingId: Long = 1L,
        startMs: Long = 0L,
        endMs: Long = 1_000L,
    ) = TranscriptSegment(
        id = id,
        recordingId = recordingId,
        startMs = startMs,
        endMs = endMs,
        text = text,
        speakerId = null,
        confidence = null,
        createdAt = 0L,
    )

    @Test
    fun extractsRussianCueSentence() {
        val result = CommitmentExtractor.extract(listOf(segment(1, "Я обещал позвонить завтра.")))
        assertEquals(1, result.size)
        assertTrue(result.first().cues.contains("обещал"))
        assertTrue(result.first().confidence in 0.0..1.0)
    }

    @Test
    fun extractsEnglishCueSentence() {
        val result = CommitmentExtractor.extract(listOf(segment(1, "I will send you the file")))
        assertEquals(1, result.size)
        assertTrue(result.first().cues.contains("i will"))
    }

    @Test
    fun ignoresSentenceWithoutCue() {
        assertEquals(
            emptyList<CommitmentCandidate>(),
            CommitmentExtractor.extract(listOf(segment(1, "Сегодня хорошая погода."))),
        )
    }

    @Test
    fun emptyInputYieldsEmpty() {
        assertTrue(CommitmentExtractor.extract(emptyList()).isEmpty())
    }

    @Test
    fun deduplicatesIdenticalSentences() {
        val result = CommitmentExtractor.extract(
            listOf(
                segment(1, "Я обещал позвонить."),
                segment(2, "я обещал позвонить"),
            ),
        )
        assertEquals(1, result.size)
    }

    @Test
    fun isBoundedToMaxCandidates() {
        val segments = (1..(CommitmentExtractor.MAX_CANDIDATES + 20)).map { index ->
            segment(index.toLong(), "Я сделаю задачу номер $index.")
        }
        assertEquals(CommitmentExtractor.MAX_CANDIDATES, CommitmentExtractor.extract(segments).size)
    }

    @Test
    fun confidenceIsAlwaysWithinBounds() {
        val segments = listOf(
            segment(1, "I will call you, I promise, remind me too."),
            segment(2, "давай, я позвоню и напишу, договорились"),
        )
        CommitmentExtractor.extract(segments).forEach {
            assertTrue("confidence ${it.confidence} out of range", it.confidence in 0.0..1.0)
        }
    }

    @Test
    fun windowTodayIsStartOfLocalDay() {
        val day = 86_400_000L
        val now = 3L * day + 12_345L
        assertEquals(3L * day, CommitmentExtractor.windowStartMillis("today", now, 0L))
    }

    @Test
    fun windowDaysSubtractsDays() {
        val day = 86_400_000L
        val now = 100L * day
        assertEquals(now - 3L * day, CommitmentExtractor.windowStartMillis("3d", now, 0L))
        assertEquals(now - 7L * day, CommitmentExtractor.windowStartMillis(null, now, 0L))
    }

    @Test
    fun windowRejectsInvalidValues() {
        val now = 100L * 86_400_000L
        assertNull(CommitmentExtractor.windowStartMillis("abc", now, 0L))
        assertNull(CommitmentExtractor.windowStartMillis("0d", now, 0L))
        assertNull(CommitmentExtractor.windowStartMillis("400d", now, 0L))
    }
}
