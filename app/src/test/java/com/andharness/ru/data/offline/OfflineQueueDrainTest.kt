package com.andharness.ru.data.offline

import com.andharness.ru.domain.agent.ToolResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [OfflineQueueDrain]: success removes, failure keeps + increments, the attempt budget
 * is bounded, and an offline device stops the drain early. No Android/WorkManager runtime involved —
 * the executor is a plain lambda.
 */
class OfflineQueueDrainTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("offline-drain-test").toFile().also { dirs += it }

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private val dns = "Unable to resolve host \"example.com\": No address associated with hostname"

    private fun queueWith(vararg ids: String): OfflineActionQueue {
        val q = OfflineActionQueue(newDir())
        ids.forEachIndexed { index, id ->
            q.enqueue("http_request", """{"url":"https://e/$id"}""", dns, now = index.toLong(), id = id)
        }
        return q
    }

    @Test
    fun drainSuccessRemovesTheAction() = runTest {
        val q = queueWith("a")
        val report = OfflineQueueDrain.drain(
            queue = q,
            isOnline = { true },
            execute = { _, _ -> ToolResult(true, "HTTP 200 OK") },
        )
        assertEquals(1, report.attempted)
        assertEquals(1, report.succeeded)
        assertEquals(0, report.remaining)
        assertTrue(q.list().isEmpty())
    }

    @Test
    fun drainFailureKeepsTheActionAndIncrementsAttempts() = runTest {
        val q = queueWith("a")
        val report = OfflineQueueDrain.drain(
            queue = q,
            isOnline = { true },
            // A logical failure is not network-related, so the drain continues past it.
            execute = { _, _ -> ToolResult(false, "HTTP 500 Server Error") },
        )
        assertEquals(1, report.failed)
        assertEquals(1, report.remaining)
        assertEquals(1, q.list().single().attempts)
        assertEquals("HTTP 500 Server Error", q.list().single().lastError)
    }

    @Test
    fun actionIsDroppedAfterMaxAttempts() = runTest {
        val q = queueWith("a")
        // Two attempts already recorded → the next failure reaches MAX_ATTEMPTS and drops it.
        q.recordFailure("a", "HTTP 500")
        q.recordFailure("a", "HTTP 500")
        val report = OfflineQueueDrain.drain(
            queue = q,
            isOnline = { true },
            execute = { _, _ -> ToolResult(false, "HTTP 500 Server Error") },
        )
        assertEquals(1, report.exhausted)
        assertEquals(0, report.remaining)
        assertTrue(q.list().isEmpty())
    }

    @Test
    fun offlineStopsEarlyWithoutTouchingTheQueue() = runTest {
        val q = queueWith("a", "b")
        var calls = 0
        val report = OfflineQueueDrain.drain(
            queue = q,
            isOnline = { false },
            execute = { _, _ -> calls++; ToolResult(true, "ok") },
        )
        assertTrue(report.stoppedOffline)
        assertEquals(0, calls)
        assertEquals(2, report.remaining)
        assertEquals(0, q.list().first().attempts)
    }

    @Test
    fun networkFailureMidDrainStopsAndLaterActionsAreUntouched() = runTest {
        val q = queueWith("a", "b")
        var calls = 0
        val report = OfflineQueueDrain.drain(
            queue = q,
            isOnline = { true },
            execute = { _, _ -> calls++; ToolResult(false, dns) },
        )
        assertEquals(1, calls)
        assertTrue(report.stoppedOffline)
        // "a" was recorded (kept, 1 attempt); "b" was not attempted at all.
        assertEquals(1, q.list().first { it.id == "a" }.attempts)
        assertEquals(0, q.list().first { it.id == "b" }.attempts)
        assertEquals(2, report.remaining)
    }

    @Test
    fun throwingToolIsCountedAsFailureAndDrainContinues() = runTest {
        val q = queueWith("a", "b")
        val report = OfflineQueueDrain.drain(
            queue = q,
            isOnline = { true },
            execute = { _, args ->
                if (args.contains("/a")) throw IllegalStateException("boom")
                ToolResult(true, "ok")
            },
        )
        assertEquals(2, report.attempted)
        assertEquals(1, report.failed)
        assertEquals(1, report.succeeded)
        assertFalse(report.stoppedOffline)
        assertEquals(1, q.count())
    }
}
