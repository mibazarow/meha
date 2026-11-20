package com.andharness.ru.domain.objective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the bounded, fair concurrency limiter behind "parallel" objectives (checklist §2):
 * the queued→running→done transitions, FIFO fairness, idempotent requests and the 1..3 clamp.
 */
class ObjectiveConcurrencyLimiterTest {

    @Test
    fun `admits up to the limit and queues the rest in FIFO order`() {
        val limiter = ObjectiveConcurrencyLimiter(2)
        assertTrue(limiter.request("a"))
        assertTrue(limiter.request("b"))
        assertFalse(limiter.request("c"))
        assertFalse(limiter.request("d"))
        assertEquals(listOf("a", "b"), limiter.runningIds())
        assertEquals(listOf("c", "d"), limiter.queuedIds())
    }

    @Test
    fun `release admits the head of the queue`() {
        val limiter = ObjectiveConcurrencyLimiter(1)
        assertTrue(limiter.request("a"))
        assertFalse(limiter.request("b"))
        assertFalse(limiter.request("c"))
        assertEquals("b", limiter.release("a"))
        assertEquals(listOf("b"), limiter.runningIds())
        assertEquals(listOf("c"), limiter.queuedIds())
        assertEquals("c", limiter.release("b"))
        assertEquals(listOf("c"), limiter.runningIds())
        assertTrue(limiter.queuedIds().isEmpty())
    }

    @Test
    fun `repeated request is idempotent for running and queued ids`() {
        val limiter = ObjectiveConcurrencyLimiter(1)
        assertTrue(limiter.request("a"))
        assertTrue(limiter.request("a"))
        assertFalse(limiter.request("b"))
        assertFalse(limiter.request("b"))
        assertEquals(listOf("a"), limiter.runningIds())
        assertEquals(listOf("b"), limiter.queuedIds())
    }

    @Test
    fun `cancel removes from both running and queue`() {
        val limiter = ObjectiveConcurrencyLimiter(2)
        assertTrue(limiter.request("a"))
        assertTrue(limiter.request("b"))
        assertFalse(limiter.request("c"))
        limiter.cancel("b")
        assertFalse(limiter.isRunning("b"))
        assertNull(limiter.release("b"))
        limiter.cancel("c")
        assertFalse(limiter.isQueued("c"))
        // Cancelling a running objective frees its slot for the next queued objective.
        assertTrue(limiter.request("d"))
        assertFalse(limiter.request("e"))
        assertEquals("e", limiter.release("d"))
    }

    @Test
    fun `setLimit clamps to one through three`() {
        val limiter = ObjectiveConcurrencyLimiter(2)
        limiter.setLimit(0)
        assertEquals(ObjectiveConcurrencyPolicy.MIN, limiter.limit)
        limiter.setLimit(9)
        assertEquals(ObjectiveConcurrencyPolicy.MAX, limiter.limit)
        limiter.setLimit(2)
        assertEquals(2, limiter.limit)
    }

    @Test
    fun `raising the limit admits queued objectives immediately`() {
        val limiter = ObjectiveConcurrencyLimiter(1)
        limiter.request("a")
        limiter.request("b")
        limiter.request("c")
        val admitted = limiter.setLimit(3)
        assertEquals(listOf("b", "c"), admitted)
        assertEquals(listOf("a", "b", "c"), limiter.runningIds())
        assertTrue(limiter.queuedIds().isEmpty())
    }

    @Test
    fun `lowering the limit does not evict running objectives`() {
        val limiter = ObjectiveConcurrencyLimiter(3)
        limiter.request("a")
        limiter.request("b")
        limiter.request("c")
        val admitted = limiter.setLimit(1)
        assertTrue(admitted.isEmpty())
        assertEquals(listOf("a", "b", "c"), limiter.runningIds())
        // The next release does not over-admit.
        limiter.release("a")
        assertEquals(listOf("b", "c"), limiter.runningIds())
    }
}
