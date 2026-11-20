package com.andharness.ru.domain.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure MCP validation/normalization/sanitization/rendering rules. */
class McpServerRulesTest {

    @Test
    fun normalizeNameTrimsCollapsesAndBounds() {
        assertEquals("Погода", McpServerRules.normalizeName("  Погода  "))
        // Internal whitespace (including a newline) is collapsed to a SINGLE space, matching the
        // "collapses internal whitespace" contract in `normalizeName`.
        assertEquals("Мой сервер", McpServerRules.normalizeName("Мой\n сервер"))
        assertNull(McpServerRules.normalizeName("   "))
        assertNull(McpServerRules.normalizeName(null))
        assertNull(McpServerRules.normalizeName("x".repeat(McpServerRules.MAX_NAME_CHARS + 1)))
    }

    @Test
    fun normalizeUrlAssumesHttpsAndRejectsBadSchemes() {
        assertEquals("https://mcp.example.com/mcp", McpServerRules.normalizeUrl("mcp.example.com/mcp"))
        assertEquals("https://mcp.example.com/mcp", McpServerRules.normalizeUrl("  https://mcp.example.com/mcp "))
        assertEquals("http://localhost:8080/messages", McpServerRules.normalizeUrl("http://localhost:8080/messages"))
        assertNull(McpServerRules.normalizeUrl("ftp://example.com"))
        assertNull(McpServerRules.normalizeUrl("https://"))
        assertNull(McpServerRules.normalizeUrl("not a url"))
        assertNull(McpServerRules.normalizeUrl(null))
        assertNull(McpServerRules.normalizeUrl("https://example.com/path#frag"))
    }

    @Test
    fun normalizeHeaderNameRejectsReservedAndMalformedNames() {
        assertEquals("Authorization", McpServerRules.normalizeHeaderName(" Authorization "))
        assertEquals("X-Api-Key", McpServerRules.normalizeHeaderName("X-Api-Key"))
        assertNull(McpServerRules.normalizeHeaderName(""))
        assertNull(McpServerRules.normalizeHeaderName("Bad Header"))
        assertNull(McpServerRules.normalizeHeaderName("Content-Type"))
        assertNull(McpServerRules.normalizeHeaderName("host"))
    }

    @Test
    fun sanitizeToolNameProducesSafeAsciiIdentifiers() {
        assertEquals("get_weather", McpServerRules.sanitizeToolName("get.weather"))
        assertEquals("read_file", McpServerRules.sanitizeToolName("Read File"))
        assertEquals("tool", McpServerRules.sanitizeToolName("///"))
        assertEquals("a_b", McpServerRules.sanitizeToolName("a---b"))
        assertFalse(McpServerRules.sanitizeToolName("weird-имя").contains('-'))
        assertTrue(McpServerRules.sanitizeToolName("x".repeat(200)).length <= 48)
    }

    @Test
    fun qualifiedToolNameCombinesServerAndTool() {
        val server = McpServer(id = "mcpABC", name = "Погода", url = "https://x.example.com")
        assertEquals("mcp_mcpabc_get_weather", McpServerRules.qualifiedToolName(server, "get.weather"))
        assertTrue(McpServerRules.qualifiedToolName(server, "t".repeat(200)).length <= 64)
    }

    @Test
    fun renderBlockEmptyStillMentionsChatToolsAndNonEmptyListsServers() {
        val empty = McpServerRules.renderBlock(emptyList())
        assertTrue(empty.contains("mcp_server_add"))
        assertTrue(empty.contains("mcp_server_remove"))

        val block = McpServerRules.renderBlock(
            listOf(
                McpServer(id = "id1", name = "Погода", url = "https://w.example.com", enabled = true),
                McpServer(id = "id2", name = "Почта", url = "https://m.example.com", enabled = false),
            )
        )
        assertTrue(block.contains("Погода"))
        assertTrue(block.contains("https://m.example.com"))
        assertTrue(block.contains("включён"))
        assertTrue(block.contains("выключен"))
    }

    @Test
    fun transportParsingIsLenientAndHonest() {
        assertEquals(McpTransport.AUTO, McpTransport.parse(null))
        assertEquals(McpTransport.AUTO, McpTransport.parse("auto"))
        assertEquals(McpTransport.STREAMABLE_HTTP, McpTransport.parse("Streamable-HTTP"))
        assertEquals(McpTransport.SSE, McpTransport.parse("sse"))
        assertEquals(McpTransport.STREAMABLE_HTTP, McpTransport.parse("http"))
        assertNull(McpTransport.parse("carrier-pigeon"))
    }
}
