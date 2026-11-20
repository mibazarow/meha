package com.andharness.ru.data.tools.communications

import com.andharness.ru.domain.communications.CommitmentCandidate
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure `commitments_extract` report formatter. It must state honestly whether
 * tasks were created, carry the "heuristic, not a guarantee" caveat, and echo each candidate.
 */
class CommitmentsExtractSupportTest {

    private fun candidate(text: String, startMs: Long = 65_000L) = CommitmentCandidate(
        text = text,
        recordingId = 7L,
        startMs = startMs,
        endMs = startMs + 2_000L,
        cues = listOf("сделаю"),
        confidence = 0.7,
    )

    @Test
    fun emptyResultSaysNothingFound() {
        val report = formatCommitmentReport(
            candidates = emptyList(),
            createdCount = 0,
            createRequested = false,
            windowLabel = "7d",
            scannedSegments = 12,
        )
        assertTrue(report.contains("не найдено"))
        assertTrue(report.contains("12"))
    }

    @Test
    fun createTrueReportsCreatedCount() {
        val report = formatCommitmentReport(
            candidates = listOf(candidate("Я сделаю отчёт")),
            createdCount = 1,
            createRequested = true,
            windowLabel = "today",
            scannedSegments = 5,
        )
        assertTrue(report.contains("Создано дел: 1"))
        assertTrue(report.contains("Я сделаю отчёт"))
        assertTrue(report.contains("01:05"))
    }

    @Test
    fun createFalseSaysNothingWritten() {
        val report = formatCommitmentReport(
            candidates = listOf(candidate("I will call you")),
            createdCount = 0,
            createRequested = false,
            windowLabel = "7d",
            scannedSegments = 3,
        )
        assertTrue(report.contains("create=false"))
        assertTrue(report.contains("эвристика"))
    }
}
