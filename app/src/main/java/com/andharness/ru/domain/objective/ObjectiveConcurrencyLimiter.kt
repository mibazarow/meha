package com.andharness.ru.domain.objective

/**
 * Bounded, fair concurrency limiter for background objective runs (checklist §2).
 *
 * It is the whole "parallelism" story, kept pure and JVM-testable (no Android, no coroutines):
 * the coordinator asks it to [request] an objective; if fewer than [limit] objectives are already
 * running the objective runs at once, otherwise it is placed at the TAIL of a FIFO queue (fair
 * round-robin — no objective can starve). When a run [release]s a slot, the head of the queue is
 * admitted and returned so the caller can start it.
 *
 * The limit is clamped to [MIN]..[MAX] (1..3); the device is never asked to run more than three
 * independent agent runs at once.
 */
class ObjectiveConcurrencyLimiter(initialLimit: Int = ObjectiveConcurrencyPolicy.DEFAULT) {

    private val queue = ArrayDeque<String>()
    private val running = LinkedHashSet<String>()

    /** Current (clamped) concurrency limit. */
    var limit: Int = ObjectiveConcurrencyPolicy.clamp(initialLimit)
        private set

    /**
     * Requests a slot for [id]. Returns true when the objective IS running after this call (either
     * it already was, or it was admitted immediately because there was room); false when it had to be
     * queued. A repeat request for an already-running/queued id is idempotent.
     */
    fun request(id: String): Boolean {
        if (id in running) return true
        if (id in queue) return false
        if (running.size < limit) {
            running.add(id)
            return true
        }
        queue.addLast(id)
        return false
    }

    /**
     * Releases a slot held by [id] (no-op when it was only queued). Returns the id admitted from the
     * queue head to fill the freed slot, or null. The caller starts the returned id.
     */
    fun release(id: String): String? {
        if (!running.remove(id)) {
            queue.remove(id)
            return null
        }
        return admit()
    }

    /** Removes [id] from both the running set and the queue (explicit stop). */
    fun cancel(id: String) {
        running.remove(id)
        queue.remove(id)
    }

    /** Clears everything (stop-all / process shutdown). */
    fun clear() {
        running.clear()
        queue.clear()
    }

    /**
     * Sets a new [limit], clamped to [MIN]..[MAX]. When it is raised, queued objectives are admitted
     * immediately; the newly admitted ids are returned so the caller can start them.
     */
    fun setLimit(newLimit: Int): List<String> {
        limit = ObjectiveConcurrencyPolicy.clamp(newLimit)
        val admitted = mutableListOf<String>()
        while (true) admitted += admit() ?: break
        return admitted
    }

    fun isRunning(id: String): Boolean = id in running
    fun isQueued(id: String): Boolean = id in queue
    fun runningIds(): List<String> = running.toList()
    fun queuedIds(): List<String> = queue.toList()

    /** Admit at most one queued id; null when full or the queue is empty. */
    private fun admit(): String? {
        if (running.size >= limit) return null
        val next = queue.removeFirstOrNull() ?: return null
        running.add(next)
        return next
    }
}
