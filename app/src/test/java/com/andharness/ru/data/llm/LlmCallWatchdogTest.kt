package com.andharness.ru.data.llm

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for the external (thread-based) watchdog that aborts a stalled LLM stream.
 *
 * These run on the JVM with NO network at all: [RecordingCall] is a fake OkHttp [Call] that just
 * records `cancel()`, and the budgets are milliseconds instead of the production seconds.
 */
class LlmCallWatchdogTest {

    private class RecordingCall : Call {
        val cancels = AtomicInteger(0)
        override fun request(): Request = Request.Builder().url("http://127.0.0.1/").build()
        override fun execute(): Response = throw IOException("not used")
        override fun enqueue(responseCallback: Callback) = throw IOException("not used")
        override fun cancel() {
            cancels.incrementAndGet()
        }

        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = cancels.get() > 0
        override fun timeout(): Timeout = Timeout.NONE
        public override fun clone(): Call = RecordingCall()
    }

    private fun awaitTill(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun idleWatchdogCancelsAStalledCall() {
        val call = RecordingCall()
        val watchdog = LlmCallWatchdog(
            call = call,
            idleProgressTimeoutSec = 1,
            maxCompletionSec = 60,
            tickMs = 50
        )
        try {
            watchdog.start()
            assertTrue(
                "a stream with no progress must be cancelled by the idle watchdog",
                awaitTill(5_000) { call.cancels.get() > 0 }
            )
            val reason = watchdog.abortReason()
            assertNotNull("the watchdog must record an honest abort reason", reason)
            assertTrue("reason must name the stall, was: $reason", reason!!.contains("поток завис"))
        } finally {
            watchdog.stop()
        }
    }

    @Test
    fun progressRefreshesTheIdleBudget() {
        val call = RecordingCall()
        val watchdog = LlmCallWatchdog(
            call = call,
            idleProgressTimeoutSec = 1,
            maxCompletionSec = 60,
            tickMs = 50
        )
        try {
            watchdog.start()
            // Real deltas keep arriving, so the idle budget never expires even though far more than
            // 1 s of wall-clock time passes.
            repeat(12) {
                watchdog.noteProgress()
                Thread.sleep(200)
            }
            assertTrue("a stream that keeps making progress must NOT be cancelled", call.cancels.get() == 0)
            assertNull(watchdog.abortReason())
        } finally {
            watchdog.stop()
        }
    }

    @Test
    fun overallCapCancelsALongRunningCall() {
        val call = RecordingCall()
        val watchdog = LlmCallWatchdog(
            call = call,
            idleProgressTimeoutSec = 3600,
            maxCompletionSec = 1,
            tickMs = 50
        )
        try {
            watchdog.start()
            assertTrue(
                "the overall wall-clock cap must cancel the call",
                awaitTill(5_000) { call.cancels.get() > 0 }
            )
            val reason = watchdog.abortReason()
            assertNotNull(reason)
            assertTrue("reason must name the overall cap, was: $reason", reason!!.contains("лимит"))
        } finally {
            watchdog.stop()
        }
    }

    /**
     * The idle abort message must be derived from the configured budget, so the user-facing text can
     * never drift from the constant it is enforced with. The clock is injected and advanced instead
     * of waiting the real 1024 s.
     */
    @Test
    fun idleAbortMessageNamesTheConfiguredBudget() {
        val call = RecordingCall()
        val now = AtomicLong(0L)
        val watchdog = LlmCallWatchdog(
            call = call,
            idleProgressTimeoutSec = LlmTimeouts.IDLE_PROGRESS_TIMEOUT_SEC,
            maxCompletionSec = LlmTimeouts.MAX_COMPLETION_SEC,
            tickMs = 20,
            clock = { now.get() },
        )
        try {
            watchdog.start()
            now.set(LlmTimeouts.IDLE_PROGRESS_TIMEOUT_SEC * 1000L)
            assertTrue(
                "the idle watchdog must cancel once its budget elapses",
                awaitTill(5_000) { call.cancels.get() > 0 }
            )
            val reason = watchdog.abortReason()
            assertNotNull("the watchdog must record an honest abort reason", reason)
            assertTrue(
                "the message must name the configured budget, was: $reason",
                reason!!.contains("${LlmTimeouts.IDLE_PROGRESS_TIMEOUT_SEC} секунд")
            )
        } finally {
            watchdog.stop()
        }
    }

    @Test
    fun stopPreventsAnyCancellation() {
        val call = RecordingCall()
        val watchdog = LlmCallWatchdog(
            call = call,
            idleProgressTimeoutSec = 1,
            maxCompletionSec = 1,
            tickMs = 50
        )
        watchdog.start()
        watchdog.stop()
        Thread.sleep(1_500)
        assertFalse("a stopped watchdog must never cancel the call", call.isCanceled())
    }
}
