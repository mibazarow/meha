package com.andharness.ru.data.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the scope-ownership defect: an agent turn used to run INLINE in the caller's
 * coroutine, so `ChatViewModel.send()` on `viewModelScope` made the turn a child of the screen's
 * scope and navigating away cancelled it. [AgentRunScope] puts the turn on an application-lifetime
 * scope instead, so only an explicit per-chat stop (or process death) can cancel it.
 *
 * These tests exercise the real production launcher without Android: a "caller" scope plays the part
 * of `viewModelScope`, and its cancellation must leave the owned turn untouched.
 */
class AgentRunScopeTest {

    private fun runScope() = AgentRunScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun `a turn keeps running after the caller scope is cancelled and still completes`() = runBlocking {
        val scope = runScope()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        var result: String? = null

        // The turn runs on the application-owned scope through AgentRunScope.
        val turn = scope.launch(chatId = 1L) {
            started.complete(Unit)
            result = release.await() // stands in for a long LLM/tool call
        }

        // The "caller" behaves like run(): it waits for the turn, and it is the ViewModel's scope.
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callerWait = callerScope.async { turn.join() }
        started.await()

        // Navigation away cancels the caller scope (viewModelScope).
        callerWait.cancel()
        callerScope.cancel()

        assertTrue("the turn must survive the caller being cancelled", turn.isActive)

        release.complete("DONE")
        withTimeout(5_000) { turn.join() }

        assertTrue(turn.isCompleted)
        assertEquals("DONE", result)
        delay(50)
        assertTrue("a finished turn is dropped from the registry", scope.jobFor(1L) == null)
    }

    @Test
    fun `cancelTurn cancels only the requested chat`() = runBlocking {
        val scope = runScope()
        val chat1 = scope.launch(1L) { CompletableDeferred<Unit>().await() }
        val chat2 = scope.launch(2L) { CompletableDeferred<Unit>().await() }
        delay(50)

        assertTrue(scope.cancelTurn(1L, "Остановлено пользователем"))
        withTimeout(5_000) { chat1.join() }

        assertTrue(chat1.isCancelled)
        assertTrue("another chat's turn must be untouched", chat2.isActive)

        assertTrue(scope.cancelTurn(2L, "Остановлено пользователем"))
        withTimeout(5_000) { chat2.join() }
        assertTrue(chat2.isCancelled)
    }

    @Test
    fun `cancelling one chat's caller scope leaves every turn running`() = runBlocking {
        val scope = runScope()
        val release = CompletableDeferred<Unit>()
        val turn1 = scope.launch(1L) { release.await() }
        val turn2 = scope.launch(2L) { release.await() }
        delay(50)

        val caller1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wait1 = caller1.async { turn1.join() }
        wait1.cancel()
        caller1.cancel()

        assertTrue(turn1.isActive)
        assertTrue(turn2.isActive)

        release.complete(Unit)
        withTimeout(5_000) {
            turn1.join()
            turn2.join()
        }
        assertTrue(turn1.isCompleted)
        assertTrue(turn2.isCompleted)
    }

    /**
     * BUG-046 regression: starting ANOTHER chat's run (or the user navigating to it) must never
     * cancel a run that is already in flight for a different chat. Each turn is owned by the
     * application scope, keyed by chatId, so a sibling launch only registers its own chat.
     */
    @Test
    fun `starting another chat's run does not cancel an active run`() = runBlocking {
        val scope = runScope()
        val release = CompletableDeferred<Unit>()
        val turnA = scope.launch(1L) { release.await() } // chat A in flight
        val turnB = scope.launch(2L) { release.await() } // chat B starts while A runs
        delay(50)

        assertTrue("chat A must keep running when chat B starts", turnA.isActive)
        assertTrue("chat B must be running", turnB.isActive)

        release.complete(Unit)
        withTimeout(5_000) {
            turnA.join()
            turnB.join()
        }
        assertTrue(turnA.isCompleted)
        assertTrue(turnB.isCompleted)
    }

    @Test
    fun `the turn job is recorded before it starts so an immediate stop is honoured`() = runBlocking {
        val scope = runScope()
        var ran = false
        var captured: Job? = null
        val turn = scope.launch(7L, onLaunched = { captured = it }) {
            delay(10_000)
            ran = true
        }
        assertEquals(turn, captured)

        assertTrue(scope.cancelTurn(7L, "stop"))
        withTimeout(5_000) { turn.join() }
        assertTrue(turn.isCancelled)
        assertFalse(ran)
    }

    @Test
    fun `one run's failure neither kills the app scope nor the other runs`() = runBlocking {
        // The application scope is a SupervisorJob (see CoroutineModule): a child that fails must not
        // cancel its siblings nor the scope itself, otherwise one broken turn would take down every
        // other concurrent turn. References:
        // https://kotlinlang.org/docs/exception-handling.html#supervision-scope
        val handler = CoroutineExceptionHandler { _, _ -> /* swallow: the run failed on purpose */ }
        val scope = AgentRunScope(CoroutineScope(SupervisorJob() + Dispatchers.Default + handler))

        val healthy = CompletableDeferred<Unit>()
        val boom = scope.launch(1L) { throw IllegalStateException("run 1 failed") }
        val sibling = scope.launch(2L) { healthy.await() }

        withTimeout(5_000) { boom.join() }
        assertTrue("the failed run completed exceptionally", boom.isCancelled)
        assertTrue("a sibling run keeps running after another fails", sibling.isActive)

        // The scope is still usable: a brand-new run can be launched and completed normally.
        healthy.complete(Unit)
        var ran = false
        val next = scope.launch(3L) { ran = true }
        withTimeout(5_000) {
            sibling.join()
            next.join()
        }
        assertTrue(sibling.isCompleted)
        assertTrue(ran)
    }
}
