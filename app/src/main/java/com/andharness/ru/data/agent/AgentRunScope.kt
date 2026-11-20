package com.andharness.ru.data.agent

import com.andharness.ru.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Launches each agent turn into an application-lifetime scope and keeps a chatId -> turn [Job]
 * registry, so an in-flight turn is owned by the AGENT, never by the UI that started it.
 *
 * Why this exists (the defect it fixes): the orchestrator used to execute the whole turn inline in
 * the caller's coroutine, so `ChatViewModel.send()` running on `viewModelScope` made the turn a
 * CHILD of that scope. Leaving the chat cancelled the ViewModel scope, which cancelled the child
 * turn ([TurnFinalizer] then published `AgentState.CANCELLED`). Structured concurrency works exactly
 * that way — a child is cancelled when its parent is:
 * https://kotlinlang.org/docs/coroutines-basics.html#structured-concurrency
 *
 * With this launcher the turn is a child of the [ApplicationScope], so only an explicit
 * [cancel]/[cancelTurn] or process death ends it; navigation cannot.
 *
 * Honesty: this is a PROCESS-lifetime scope. If Android kills the app process, [ApplicationScope] is
 * gone and the in-flight turn is lost (nothing here pretends otherwise); long objectives can still
 * be resumed by the existing objective runner.
 *
 * References (structured concurrency / cancellation):
 * - https://kotlinlang.org/docs/coroutines-basics.html#structured-concurrency
 * - https://kotlinlang.org/docs/cancellation-and-timeouts.html
 */
@Singleton
class AgentRunScope @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope
) {

    /** chatId -> the coroutine of its current turn (running or waiting for a slot). */
    private val jobs = ConcurrentHashMap<Long, Job>()

    /**
     * Starts [block] on the application scope for [chatId] and returns its [Job].
     *
     * [onLaunched] runs AFTER the job is registered but BEFORE it starts, so the host can record the
     * exact job it owns (the orchestrator stores it in its per-chat `RunState.job`) without a race
     * with a very short turn. The entry is removed when the job completes.
     */
    fun launch(
        chatId: Long,
        onLaunched: (Job) -> Unit = {},
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        jobs[chatId] = job
        onLaunched(job)
        job.invokeOnCompletion { jobs.remove(chatId, job) }
        job.start()
        return job
    }

    /**
     * Cancels exactly [chatId]'s turn (running or waiting for a slot). Returns true when a turn was
     * registered and therefore cancelled; false when there was none. Other chats are untouched.
     */
    fun cancelTurn(chatId: Long, reason: String): Boolean {
        val job = jobs[chatId] ?: return false
        job.cancel(CancellationException(reason))
        return true
    }

    /** Whether [chatId] currently has a registered turn coroutine (running or queued). */
    fun isActive(chatId: Long): Boolean = jobs[chatId]?.isActive == true

    /** The registered turn [Job] for [chatId], or null. Exposed for ownership assertions/tests. */
    fun jobFor(chatId: Long): Job? = jobs[chatId]
}
