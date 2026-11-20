package com.andharness.ru.data.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotGateTest {

    @Test
    fun everyRequestGetsItsOwnFreshGate() = runBlocking {
        val gate = OneShotGate()

        val first = gate.open()
        assertTrue(gate.resolve(true))
        assertTrue("first request is confirmed", first.await())

        // The bug this guards against: reusing one completed deferred would auto-confirm every later
        // dangerous action. A fresh gate must stay open until it is explicitly resolved.
        val second = gate.open()
        assertFalse("the second gate must not be pre-confirmed", second.isCompleted)
        assertTrue(gate.resolve(true))
        assertTrue("second request is confirmed only after its own prompt", second.await())
    }

    @Test
    fun rejectionResolvesOnlyTheCurrentGate() = runBlocking {
        val gate = OneShotGate()

        val first = gate.open()
        assertTrue(gate.resolve(false))
        assertFalse(first.await())

        val second = gate.open()
        assertFalse(second.isCompleted)
        assertTrue(gate.resolve(true))
        assertTrue(second.await())
    }

    @Test
    fun resolvingWithNothingPendingIsANoOp() {
        val gate = OneShotGate()

        assertFalse("a stale UI tap must not resolve anything", gate.resolve(true))
    }

    @Test
    fun clearDoesNotDropANewerGate() = runBlocking {
        val gate = OneShotGate()

        val first = gate.open()
        val second = gate.open() // replaces the slot, as a newer request would
        gate.clear(first)

        assertFalse(second.isCompleted)
        assertTrue(gate.resolve(true))
        assertTrue(second.await())
    }
}
