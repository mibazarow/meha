package com.andharness.ru.domain.agent

/**
 * Pure, Android-free, JVM-tested admission control for several simultaneous agent runs.
 *
 * It is deliberately free of coroutines/Android so the whole "concurrency + priority + queueing"
 * rule set is unit-tested without a device: the orchestrator asks [request] for a chat and starts
 * the turn only when the answer is [Admission.ADMITTED]; on [Admission.QUEUED] it suspends on its own
 * gate and is woken by the caller when [release]/[cancel]/[setLimit] return its id as newly admitted.
 *
 * Rules:
 * - at most [limit] runs (clamped to [MIN_LIMIT]..[MAX_LIMIT] = 1..3, default [DEFAULT_LIMIT] = 2);
 * - an INTERACTIVE run is admitted whenever `running < limit`; a BACKGROUND run may occupy at most
 *   `limit - 1` slots (so one slot is always reserved for a chat), and may additionally fill a slot
 *   only when the limiter is completely idle and no interactive run is waiting;
 * - a request that cannot be admitted now is queued FIFO within its priority (INTERACTIVE before
 *   BACKGROUND), up to [queueCapacity] entries; beyond that it is [Admission.REJECTED] honestly
 *   (the caller reports BUSY only then);
 * - [backgroundRunsToYield] tells the orchestrator which BACKGROUND runs should pause at their next
 *   checkpoint so a waiting INTERACTIVE run can take the slot without waiting for the whole step.
 *
 * Design references (official Kotlin coroutines docs):
 * - structured concurrency / cancellation: https://kotlinlang.org/docs/coroutines-basics.html#structured-concurrency
 * - shared mutable state and confinement: https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html
 * - `Semaphore` (the standard bounded-concurrency primitive this class replaces with an
 *   explicit priority+FIFO policy): https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-semaphore/
 */
class RunConcurrencyLimiter(
    initialLimit: Int = DEFAULT_LIMIT,
    /** Upper bound on the number of waiting runs; beyond it a request is honestly REJECTED. */
    val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
) {

    /** Result of [request]. */
    enum class Admission {
        /** The run may start now. */
        ADMITTED,

        /** The run was accepted but must wait for a slot (woken by the returned id from a release). */
        QUEUED,

        /** The queue is full: the run was NOT accepted (the only honest BUSY case). */
        REJECTED
    }

    private data class Queued(val chatId: Long, val priority: AgentRunPriority)

    private val running = LinkedHashMap<Long, AgentRunPriority>()
    private val queue = ArrayDeque<Queued>()

    /** Current (clamped) concurrency limit. */
    var limit: Int = clamp(initialLimit)
        private set

    /** Slots a BACKGROUND run may occupy while interactive capacity is reserved: `limit - 1`. */
    val backgroundCap: Int get() = (limit - 1).coerceAtLeast(0)

    /**
     * Requests a slot for [chatId]. Idempotent: a run that already is running returns ADMITTED, one
     * already waiting returns QUEUED. Otherwise the run is admitted, queued or rejected.
     */
    fun request(chatId: Long, priority: AgentRunPriority): Admission {
        if (running.containsKey(chatId)) return Admission.ADMITTED
        if (queue.any { it.chatId == chatId }) return Admission.QUEUED
        if (canAdmit(priority)) {
            running[chatId] = priority
            return Admission.ADMITTED
        }
        if (queue.size < queueCapacity) {
            queue.addLast(Queued(chatId, priority))
            return Admission.QUEUED
        }
        return Admission.REJECTED
    }

    /**
     * Releases the slot held by [chatId] (no-op for a merely queued id) and returns the ids admitted
     * from the queue to fill the freed capacity. The caller starts/wakes exactly those ids.
     */
    fun release(chatId: Long): List<Long> =
        if (running.remove(chatId) != null) drain() else emptyList()

    /**
     * Removes [chatId] from both the running set and the queue (explicit stop). When it was running,
     * its slot is offered to the queue and the newly admitted ids are returned.
     */
    fun cancel(chatId: Long): List<Long> {
        val wasRunning = running.remove(chatId) != null
        queue.removeAll { it.chatId == chatId }
        return if (wasRunning) drain() else emptyList()
    }

    /**
     * Sets a new [limit] (clamped to 1..3). Raising it admits queued runs immediately; the newly
     * admitted ids are returned.
     */
    fun setLimit(newLimit: Int): List<Long> {
        val clamped = clamp(newLimit)
        if (clamped == limit) return emptyList()
        limit = clamped
        return drain()
    }

    fun isRunning(chatId: Long): Boolean = running.containsKey(chatId)
    fun isQueued(chatId: Long): Boolean = queue.any { it.chatId == chatId }
    fun runningIds(): List<Long> = running.keys.toList()
    fun queuedIds(): List<Long> = queue.map { it.chatId }
    fun runningWith(priority: AgentRunPriority): List<Long> =
        running.filterValues { it == priority }.keys.toList()
    fun queuedWith(priority: AgentRunPriority): List<Long> =
        queue.filter { it.priority == priority }.map { it.chatId }

    /**
     * BACKGROUND runs that should pause at their next checkpoint because a waiting INTERACTIVE run
     * cannot be admitted otherwise. Returns at most as many ids as are needed to free the slots the
     * interactive run(s) require; empty when no interactive run is waiting or a slot is already free.
     */
    fun backgroundRunsToYield(): List<Long> {
        val waiting = queuedWith(AgentRunPriority.INTERACTIVE).size
        if (waiting == 0) return emptyList()
        val free = (limit - running.size).coerceAtLeast(0)
        val need = (waiting - free).coerceAtLeast(0)
        if (need == 0) return emptyList()
        return runningWith(AgentRunPriority.BACKGROUND).take(need)
    }

    private fun canAdmit(priority: AgentRunPriority): Boolean {
        if (running.size >= limit) return false
        if (priority == AgentRunPriority.INTERACTIVE) return true
        if (runningWith(AgentRunPriority.BACKGROUND).size < backgroundCap) return true
        // Idle-fill: with nothing running at all, a background run may take the free slot even when
        // interactive capacity is reserved. The first interactive request then queues it out via
        // [backgroundRunsToYield], so a chat is still never rejected.
        return running.isEmpty()
    }

    /** Admits as many queued runs as the current limit/priority allow, FIFO within each class. */
    private fun drain(): List<Long> {
        val admitted = mutableListOf<Long>()
        while (true) {
            val next = nextAdmissible() ?: break
            queue.remove(next)
            running[next.chatId] = next.priority
            admitted += next.chatId
        }
        return admitted
    }

    private fun nextAdmissible(): Queued? {
        val interactive = queue.firstOrNull { it.priority == AgentRunPriority.INTERACTIVE }
        if (interactive != null && canAdmit(AgentRunPriority.INTERACTIVE)) return interactive
        val background = queue.firstOrNull { it.priority == AgentRunPriority.BACKGROUND }
        if (background != null && canAdmit(AgentRunPriority.BACKGROUND)) return background
        return null
    }

    companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 3
        const val DEFAULT_LIMIT = 2
        const val DEFAULT_QUEUE_CAPACITY = 16

        fun clamp(value: Int): Int = value.coerceIn(MIN_LIMIT, MAX_LIMIT)
    }
}
