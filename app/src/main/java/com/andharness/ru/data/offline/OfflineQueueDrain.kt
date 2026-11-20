package com.andharness.ru.data.offline

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.offline.OfflineQueuePolicy
import kotlinx.coroutines.CancellationException

/**
 * Drains an [OfflineActionQueue] by re-invoking each queued tool call through the caller-supplied
 * [execute] — the SAME dispatch path the agent uses (`ToolRegistry.execute`). Android-free so the
 * success/failure/attempt semantics are exercised on the JVM (`OfflineQueueDrainTest`); the Worker
 * only supplies the real executor and an online check.
 *
 * Semantics (each run handles every currently-queued action at most once, oldest first):
 * - **success** → the action is REMOVED.
 * - **failure** → the attempt is recorded ([OfflineActionQueue.recordFailure], incrementing
 *   `attempts` + storing `lastError`) and the action is KEPT; once `attempts` reaches
 *   [MAX_ATTEMPTS] it is REMOVED (the retry budget is exhausted) so the queue can never grow
 *   forever and the worker can finish.
 * - **offline** → processing stops at once ([Report.stoppedOffline]) and the rest of the queue is
 *   left untouched so the next connected run picks it up.
 *
 * A throwing tool is caught and counted as a failure, so one bad handler can never abort the drain.
 * `CancellationException` is rethrown so coroutine cancellation still works.
 */
object OfflineQueueDrain {

    /** Maximum number of drain attempts per action before it is dropped (bounded retries). */
    const val MAX_ATTEMPTS = 3

    /** Honest summary of one drain run. */
    data class Report(
        /** Actions whose tool was actually re-invoked this run. */
        val attempted: Int,
        /** Actions whose retry succeeded and were removed. */
        val succeeded: Int,
        /** Actions whose retry failed (and were kept or exhausted). */
        val failed: Int,
        /** Actions removed because they reached [MAX_ATTEMPTS]. */
        val exhausted: Int,
        /** Actions still queued after this run. */
        val remaining: Int,
        /** True when the drain stopped early because the device was (or went) offline. */
        val stoppedOffline: Boolean,
    )

    suspend fun drain(
        queue: OfflineActionQueue,
        isOnline: () -> Boolean,
        execute: suspend (toolName: String, argumentsJson: String) -> ToolResult,
    ): Report {
        var attempted = 0
        var succeeded = 0
        var failed = 0
        var exhausted = 0
        var stoppedOffline = false

        for (action in queue.list()) {
            if (!isOnline()) {
                stoppedOffline = true
                break
            }
            val result = try {
                execute(action.toolName, action.argumentsJson)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                ToolResult(false, "Ошибка инструмента ${action.toolName}: ${e.message}")
            }
            attempted++

            if (result.success) {
                queue.remove(action.id)
                succeeded++
                continue
            }

            // Failure: record the honest reason and keep the action until its budget is spent.
            val updated = queue.recordFailure(action.id, result.output)
            failed++
            val attempts = updated?.attempts ?: (action.attempts + 1)
            if (attempts >= MAX_ATTEMPTS) {
                queue.remove(action.id)
                exhausted++
            }
            // A still-network failure means connectivity did not come back: stop the drain now
            // rather than burning every remaining action's attempt in one offline blip.
            if (!result.success && OfflineQueuePolicy.isNetworkError(result.output)) {
                stoppedOffline = true
                break
            }
        }

        return Report(
            attempted = attempted,
            succeeded = succeeded,
            failed = failed,
            exhausted = exhausted,
            remaining = queue.count(),
            stoppedOffline = stoppedOffline,
        )
    }
}
