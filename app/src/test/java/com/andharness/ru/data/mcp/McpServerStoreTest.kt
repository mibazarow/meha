package com.andharness.ru.data.mcp

import com.andharness.ru.domain.mcp.McpServerCodec
import com.andharness.ru.domain.mcp.McpServerRules
import com.andharness.ru.domain.mcp.McpTransport
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
 * JVM tests for the Android-free [McpServerStore]: real file CRUD, persistence across instances,
 * name/id lookup, the 20-server cap (reject, never drop), enable/disable, corrupt-file degradation,
 * and the security invariant that **no token ever reaches the JSON file**.
 */
class McpServerStoreTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("mcp-store-test").toFile().also { dirs += it }

    private fun store(dir: File = newDir()) = McpServerStore(dir)

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun addListGetDeleteRoundTrip() {
        val store = store()
        assertTrue(store.list().isEmpty())

        val created = store.put(null, "Погода", "https://mcp.example.com/mcp")
        assertEquals(McpWriteOutcome.CREATED, created.outcome)
        assertNotNull(created.server)
        assertEquals(1, store.count())

        val id = created.server!!.id
        assertEquals("Погода", store.get("Погода")?.name)
        assertEquals("Погода", store.get("погода")?.name) // case-insensitive name lookup
        assertEquals(id, store.get(id)?.id)

        val removed = store.delete("Погода")
        assertNotNull(removed)
        assertTrue(store.list().isEmpty())
        assertNull(store.delete("нет такого"))
    }

    @Test
    fun reAddingTheSameNameUpdatesAndReEnables() {
        val store = store()
        val first = store.put(null, "Погода", "https://a.example.com/mcp").server!!
        assertTrue(store.setEnabled(first.id, false))
        assertFalse(store.list().single().enabled)

        val second = store.put(null, "Погода", "https://b.example.com/mcp", McpTransport.SSE)
        assertEquals(McpWriteOutcome.UPDATED, second.outcome)
        assertEquals(1, store.count())
        assertEquals(first.id, second.server!!.id)
        assertEquals("https://b.example.com/mcp", second.server!!.url)
        assertEquals(McpTransport.SSE, second.server!!.transport)
        assertTrue(second.server!!.enabled)
    }

    @Test
    fun invalidInputIsRejected() {
        val store = store()
        assertEquals(McpWriteOutcome.INVALID, store.put(null, " ", "https://x.example.com").outcome)
        assertEquals(McpWriteOutcome.INVALID, store.put(null, "x", "ftp://x.example.com").outcome)
        assertEquals(McpWriteOutcome.INVALID, store.put(null, "x", "https://").outcome)
        assertEquals(
            McpWriteOutcome.NOT_FOUND,
            store.put("missing-id", "x", "https://x.example.com").outcome
        )
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun addingBeyondTwentyIsRejectedNotDropped() {
        val store = store()
        repeat(McpServerCodec.MAX_SERVERS) { index ->
            assertEquals(
                McpWriteOutcome.CREATED,
                store.put(null, "s$index", "https://s$index.example.com/mcp").outcome
            )
        }
        assertEquals(McpServerCodec.MAX_SERVERS, store.count())
        assertEquals(
            McpWriteOutcome.LIMIT,
            store.put(null, "overflow", "https://overflow.example.com/mcp").outcome
        )
        assertEquals(McpServerCodec.MAX_SERVERS, store.count()) // nothing was dropped
        assertNull(store.get("overflow"))
    }

    @Test
    fun persistsAcrossInstances() {
        val dir = newDir()
        val first = store(dir)
        val id = first.put(null, "Почта", "https://mail.example.com/mcp", McpTransport.STREAMABLE_HTTP).server!!.id
        first.setEnabled(id, false)

        val reopened = store(dir)
        assertEquals(1, reopened.count())
        assertEquals("Почта", reopened.get(id)?.name)
        assertFalse(reopened.get(id)!!.enabled)
    }

    @Test
    fun corruptFileDegradesToEmptyList() {
        val dir = newDir()
        File(dir, McpServerStore.FILE_NAME).writeText("{ not json ]")
        assertTrue(store(dir).list().isEmpty())
    }

    @Test
    fun codecDropsInvalidRecordsAndCapsTheList() {
        val valid = (0 until McpServerCodec.MAX_SERVERS + 5).joinToString(",") {
            """{"id":"id$it","name":"n$it","url":"https://n$it.example.com","transport":"AUTO","enabled":true}"""
        }
        val blob = """[{"id":"","name":"broken","url":"https://x.example.com"},$valid]"""
        val decoded = McpServerCodec.decodeServers(blob)
        assertEquals(McpServerCodec.MAX_SERVERS, decoded.size)
        assertTrue(decoded.all { it.id.isNotBlank() && it.name.isNotBlank() && it.url.isNotBlank() })
    }

    @Test
    fun jsonFileNeverContainsSecretFields() {
        val dir = newDir()
        val store = store(dir)
        store.put(null, "Секрет", "https://secret.example.com/mcp", authHeaderName = "Authorization")

        val text = File(dir, McpServerStore.FILE_NAME).readText()
        // The model has no token field: only the (non-sensitive) header NAME is persisted.
        assertTrue("auth header name must be persisted", text.contains("authHeaderName"))
        val lowered = text.lowercase()
        assertFalse("a token field must never be persisted: $text", lowered.contains("token"))
        assertFalse("an auth value field must never be persisted: $text", lowered.contains("authheadervalue"))
        assertFalse(lowered.contains("auth_header_value"))
    }

    @Test
    fun renderBlockAlwaysMentionsChatManagement() {
        val empty = store().renderBlock()
        assertTrue(empty.contains("mcp_server_add"))
        assertTrue(empty.contains("mcp_server_test"))

        val store = store()
        store.put(null, "Погода", "https://weather.example.com/mcp")
        val block = store.renderBlock()
        assertTrue(block.contains("Погода"))
        assertTrue(block.contains("https://weather.example.com/mcp"))
        assertTrue(block.length <= McpServerRules.MAX_BLOCK_CHARS)
    }
}
