package com.andharness.ru.data.agent

import kotlinx.coroutines.CompletableDeferred

/**
 * A one-shot, single-slot confirmation gate.
 *
 * A [CompletableDeferred] can only be completed once, so reusing a single instance for a whole agent
 * run would make every gate after the first resolve instantly. Instead every request opens a FRESH
 * gate: the UI resolves whichever gate is currently open, and the waiting coroutine clears it once
 * it has been resolved. There is therefore exactly one real prompt per request.
 *
 * The gate is resolved from the UI thread and awaited from the agent's IO coroutine, so the slot is
 * guarded by a lock and marked volatile for cross-thread visibility.
 */
internal class OneShotGate {

    private val lock = Any()

    @Volatile
    private var current: CompletableDeferred<Boolean>? = null

    /** Opens a fresh gate for one request and returns it. */
    fun open(): CompletableDeferred<Boolean> {
        val gate = CompletableDeferred<Boolean>()
        synchronized(lock) { current = gate }
        return gate
    }

    /**
     * Completes the currently open gate, if any. Returns true when a pending gate was resolved
     * (false when nothing is waiting, e.g. a stale UI tap).
     */
    fun resolve(value: Boolean): Boolean {
        val gate = synchronized(lock) {
            val open = current ?: return false
            current = null
            open
        }
        return gate.complete(value)
    }

    /** Clears [gate] only if it is still the current one (a newer request may have replaced it). */
    fun clear(gate: CompletableDeferred<Boolean>) {
        synchronized(lock) { if (current === gate) current = null }
    }
}
