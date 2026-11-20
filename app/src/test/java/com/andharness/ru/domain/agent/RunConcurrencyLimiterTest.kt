package com.andharness.ru.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the bounded, priority-aware admission control behind concurrent agent runs: an
 * interactive chat must never be rejected while a background objective runs, background work must
 * reserve capacity and yield, and the only honest BUSY is a full queue.
 *
 * All pure JVM: no Android, no coroutines, no database.
 */
class RunConcurrencyLimiterTest {

    private val interactive = AgentRunPriority.INTERACTIVE
    private val background = AgentRunPriority.BACKGROUND

    @Test
    fun `admits up to the limit and queues the rest FIFO`() {
        val limiter = RunConcurrencyLimiter(2)
        assertEquals(RunConcurrencyLimiter.Admission.ADMITTED, limiter.request(1L, interactive))
        assertEquals(RunConcurrencyLimiter.Admission.ADMITTED, limiter.request(2L, interactive))
        assertEquals(RunConcurrencyLimiter.Admission.QUEUED, limiter.request(3L, interactive))
        assertEquals(RunConcurrencyLimiter.Admission.QUEUED, limiter.request(4L, interactive))
        assertEquals(listOf(1L, 2L), limiter.runningIds())
        assertEquals(listOf(3L, 4L), limiter.queuedIds())
    }

    @Test
    fun `an interactive run is admitted while a background run is active`() {
        val limiter = RunConcurrencyLimiter(2)
        assertEquals(RunConcurrencyLimiter.Admission.ADMITTED, limiter.request(10L, background))
        // The chat must NOT be rejected or even queued: background work reserves only one slot.
        assertEquals(RunConcurrencyLimiter.Admission.ADMITTED, limiter.request(20L, interactive))
        assertEquals(2, limiter.runningIds().size)
        assertTrue(limiter.isRunning(20L))
    }

    @Test
    fun `background reserves the last slot so two backgrounds cannot fill the limit`() {
        val limiter = RunConcurrencyLimiter(2)
        limiter.request(1L, background)
        limiter.request(2L, background)
        // Only one background slot exists at limit 2: the second background waits, interactive does not.
        assertEquals(listOf(1L), limiter.runningWith(background))
        assertTrue(limiter.isQueued(2L))
        assertEquals(RunConcurrencyLimiter.Admission.ADMITTED, limiter.request(3L, interactive))
    }

    @Test
    fun `interactive is never rejected while only background work is running`() {
        val limiter = RunConcurrencyLimiter(2)
        // Fill both slots with background work where allowed, plus a queued background.
        limiter.request(1L, background)
        limiter.request(2L, background)
        val admission = limiter.request(99L, interactive)
        assertTrue(
            "interactive must be admitted or queued, never rejected",
            admission == RunConcurrencyLimiter.Admission.ADMITTED ||
                admission == RunConcurrencyLimiter.Admission.QUEUED
        )
    }

    @Test
    fun `at a single slot a background run yields to a waiting interactive run`() {
        val limiter = RunConcurrencyLimiter(1)
        // Idle device: the background run may use the only slot.
        assertEquals(RunConcurrencyLimiter.Admission.ADMITTED, limiter.request(7L, background))
        // A chat arrives: it is QUEUED (honest), not rejected, and the background is asked to yield.
        assertEquals(RunConcurrencyLimiter.Admission.QUEUED, limiter.request(8L, interactive))
        assertEquals(listOf(7L), limiter.backgroundRunsToYield())
        // When the background yields, the chat takes the slot.
        assertEquals(listOf(8L), limiter.release(7L))
        assertTrue(limiter.isRunning(8L))
        assertTrue(limiter.backgroundRunsToYield().isEmpty())
    }

    @Test
    fun `background runs are not asked to yield when no interactive run waits`() {
        val limiter = RunConcurrencyLimiter(2)
        limiter.request(1L, background)
        assertTrue(limiter.backgroundRunsToYield().isEmpty())
    }

    @Test
    fun `queued interactive runs are admitted before queued background runs`() {
        val limiter = RunConcurrencyLimiter(1)
        limiter.request(1L, interactive)
        // Queue a background first, then an interactive: priority, not arrival, decides admission.
        assertEquals(RunConcurrencyLimiter.Admission.QUEUED, limiter.request(2L, background))
        assertEquals(RunConcurrencyLimiter.Admission.QUEUED, limiter.request(3L, interactive))
        assertEquals(listOf(3L), limiter.release(1L))
        assertTrue(limiter.isRunning(3L))
        assertTrue(limiter.isQueued(2L))
    }

    @Test
    fun `only a full queue rejects honestly`() {
        val limiter = RunConcurrencyLimiter(1, queueCapacity = 2)
        limiter.request(1L, background)
        assertEquals(RunConcurrencyLimiter.Admission.QUEUED, limiter.request(2L, interactive))
        assertEquals(RunConcurrencyLimiter.Admission.QUEUED, limiter.request(3L, interactive))
        // Queue (2) is full: this is the one honest BUSY, with the run not accepted at all.
        assertEquals(RunConcurrencyLimiter.Admission.REJECTED, limiter.request(4L, interactive))
        assertFalse(limiter.isQueued(4L))
    }

    @Test
    fun `repeated request is idempotent for running and queued ids`() {
        val limiter = RunConcurrencyLimiter(1)
        assertEquals(RunConcurrencyLimiter.Admission.ADMITTED, limiter.request(1L, interactive))
        assertEquals(RunConcurrencyLimiter.Admission.ADMITTED, limiter.request(1L, interactive))
        assertEquals(RunConcurrencyLimiter.Admission.QUEUED, limiter.request(2L, background))
        assertEquals(RunConcurrencyLimiter.Admission.QUEUED, limiter.request(2L, background))
        assertEquals(listOf(1L), limiter.runningIds())
        assertEquals(listOf(2L), limiter.queuedIds())
    }

    @Test
    fun `cancel targets only the given chat and admits the queue`() {
        val limiter = RunConcurrencyLimiter(2)
        limiter.request(1L, interactive)
        limiter.request(2L, interactive)
        limiter.request(3L, background)
        // Cancelling chat 1 frees its slot for the queued background; chat 2 is untouched.
        assertEquals(listOf(3L), limiter.cancel(1L))
        assertTrue(limiter.isRunning(3L))
        assertTrue(limiter.isRunning(2L))
        assertFalse(limiter.isRunning(1L))
    }

    @Test
    fun `cancelling a queued chat does not disturb the running ones`() {
        val limiter = RunConcurrencyLimiter(1)
        limiter.request(1L, interactive)
        limiter.request(2L, background)
        assertTrue(limiter.cancel(2L).isEmpty())
        assertFalse(limiter.isQueued(2L))
        assertTrue(limiter.isRunning(1L))
    }

    @Test
    fun `a power downgrade to one slot does not evict running runs and is clamped`() {
        val limiter = RunConcurrencyLimiter(3)
        limiter.request(1L, interactive)
        limiter.request(2L, interactive)
        limiter.request(3L, interactive)
        // Downgrade to 1 (battery saver / thermal / low battery).
        assertTrue(limiter.setLimit(1).isEmpty())
        assertEquals(1, limiter.limit)
        assertEquals(3, limiter.runningIds().size)
        // The next release does not over-admit under the tightened limit.
        limiter.release(1L)
        assertEquals(listOf(2L, 3L), limiter.runningIds())
        // The clamp never allows more than three.
        limiter.setLimit(9)
        assertEquals(RunConcurrencyLimiter.MAX_LIMIT, limiter.limit)
        limiter.setLimit(0)
        assertEquals(RunConcurrencyLimiter.MIN_LIMIT, limiter.limit)
    }

    @Test
    fun `raising the limit admits queued runs immediately`() {
        val limiter = RunConcurrencyLimiter(1)
        limiter.request(1L, interactive)
        limiter.request(2L, background)
        limiter.request(3L, background)
        assertEquals(listOf(2L, 3L), limiter.setLimit(3))
        assertTrue(limiter.queuedIds().isEmpty())
    }

    @Test
    fun `a degraded single-slot device still runs a background objective when idle`() {
        val limiter = RunConcurrencyLimiter(1)
        // Nothing interactive is waiting, so the background may use the idle slot...
        assertEquals(RunConcurrencyLimiter.Admission.ADMITTED, limiter.request(5L, background))
        // ...and a second background waits, never filling an interactive reservation.
        assertEquals(RunConcurrencyLimiter.Admission.QUEUED, limiter.request(6L, background))
    }
}
