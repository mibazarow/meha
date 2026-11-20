package com.andharness.ru.data.offline

import com.andharness.ru.domain.offline.OfflineQueueCodec
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the file-backed [OfflineActionQueue]. It is Android-free by construction (it only
 * takes a directory), so real file CRUD, persistence across instances, the ~50 bound, attempt
 * accounting and corrupt-file degradation are all exercised on a temp directory.
 */
class OfflineActionQueueTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("offline-queue-test").toFile().also { dirs += it }

    private fun queue(dir: File = newDir()) = OfflineActionQueue(dir)

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private val dns = "Unable to resolve host \"example.com\": No address associated with hostname"

    private fun enqueue(
        q: OfflineActionQueue,
        tool: String = "http_request",
        args: String = """{"url":"https://example.com"}""",
        now: Long = System.currentTimeMillis(),
        id: String = java.util.UUID.randomUUID().toString(),
    ) = q.enqueue(tool, args, dns, now = now, id = id)

    @Test
    fun enqueueStoresOriginalCall() {
        val q = queue()
        val stored = enqueue(q, id = "a")
        assertNotNull(stored)
        assertEquals(1, q.count())
        assertEquals("http_request", q.list().single().toolName)
        assertEquals("""{"url":"https://example.com"}""", q.list().single().argumentsJson)
        assertEquals(0, q.list().single().attempts)
        assertNotNull(q.list().single().lastError)
    }

    @Test
    fun enqueueAppliesPolicyAndRejectsUnsafeCalls() {
        val q = queue()
        assertNull(
            "an irreversible local action is never queued",
            q.enqueue("sms_send", """{"to":"123"}""", dns)
        )
        assertNull(
            "a logical failure is not queued",
            q.enqueue("http_request", """{"url":"x"}""", "HTTP 404")
        )
        assertNull(q.enqueue("", "{}", dns))
        assertTrue(q.list().isEmpty())
    }

    @Test
    fun orderingIsOldestFirst() {
        val q = queue()
        enqueue(q, id = "first", now = 100L)
        enqueue(q, id = "second", now = 200L)
        enqueue(q, id = "third", now = 300L)
        assertEquals(listOf("first", "second", "third"), q.list().map { it.id })
    }

    @Test
    fun trimsToMaxKeepingNewest() {
        val q = queue()
        repeat(OfflineQueueCodec.MAX_ACTIONS + 5) { i -> enqueue(q, id = "id$i", now = i.toLong()) }
        val all = q.list()
        assertEquals(OfflineQueueCodec.MAX_ACTIONS, all.size)
        assertEquals("id5", all.first().id)
        assertEquals("id${OfflineQueueCodec.MAX_ACTIONS + 4}", all.last().id)
    }

    @Test
    fun recordFailureIncrementsAttemptsAndKeepsLastError() {
        val q = queue()
        enqueue(q, id = "a")
        val updated = q.recordFailure("a", "HTTP 500")
        assertEquals(1, updated?.attempts)
        assertEquals("HTTP 500", updated?.lastError)
        assertEquals(1, q.count())
        assertEquals(2, q.recordFailure("a", "HTTP 503")?.attempts)
        assertNull(q.recordFailure("нет такого", "x"))
    }

    @Test
    fun removeDeletesById() {
        val q = queue()
        enqueue(q, id = "a")
        enqueue(q, id = "b")
        assertTrue(q.remove("a"))
        assertFalse(q.remove("a"))
        assertEquals(listOf("b"), q.list().map { it.id })
    }

    @Test
    fun persistsAcrossStoreInstances() {
        val dir = newDir()
        val q = queue(dir)
        enqueue(q, id = "a")
        q.recordFailure("a", "timeout")
        val reopened = OfflineActionQueue(dir)
        assertEquals(1, reopened.count())
        assertEquals(1, reopened.list().single().attempts)
        assertEquals("timeout", reopened.list().single().lastError)
    }

    @Test
    fun countStateFlowReflectsChanges() {
        val q = queue()
        assertEquals(0, q.count.value)
        enqueue(q, id = "a")
        assertEquals(1, q.count.value)
        q.remove("a")
        assertEquals(0, q.count.value)
    }

    @Test
    fun corruptFileDegradesToEmptyQueue() {
        val dir = newDir()
        File(dir, OfflineActionQueue.FILE_NAME).writeText("not json at all")
        val q = queue(dir)
        assertTrue(q.list().isEmpty())
        // Still usable after a corrupt file.
        assertNotNull(enqueue(q, id = "a"))
        assertEquals(1, q.count())
    }
}
