package com.andharness.ru.data.mcp

import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.domain.mcp.McpServer
import com.andharness.ru.domain.mcp.McpServerRules
import com.andharness.ru.domain.mcp.McpToolInfo
import com.andharness.ru.domain.mcp.McpTransport
import java.nio.file.Files
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [McpToolProvider] against a real OkHttp [MockWebServer] (same harness as
 * `McpClientTest`): tool discovery → `Tool` mapping, schema flattening, TTL caching, per-server
 * fail-safe skipping, and `tools/call` argument assembly.
 */
class McpToolProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var store: McpServerStore
    private lateinit var clock: FakeClock

    private class FakeClock(var now: Long = 1_000_000L) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zoneId(): ZoneId = ZoneId.of("UTC")
        override fun locale(): Locale = Locale.ROOT
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val dir = Files.createTempDirectory("mcp-provider").toFile()
        store = McpServerStore(dir)
        clock = FakeClock()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(): McpToolProvider =
        McpToolProvider(store, McpTokenLookup { null }, McpClient(), clock)

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private val initResult =
        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18",""" +
            """"serverInfo":{"name":"demo-server","version":"1.0"},"capabilities":{}}}"""

    /** Queues the 3 HTTP exchanges (initialize, initialized notification, tools/list) of one session. */
    private fun enqueueListTools(toolsJson: String) {
        server.enqueue(json(initResult))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":$toolsJson}}"""))
    }

    private fun addServer(transport: McpTransport = McpTransport.STREAMABLE_HTTP): McpServer {
        val url = server.url("/mcp").toString()
        val result = store.put(null, "demo", url, transport)
        return result.server!!
    }

    private val weatherSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("city") { put("type", "string") }
            putJsonObject("days") { put("type", "integer") }
            putJsonObject("options") { put("type", "object") }
        }
        putJsonArray("required") { add(JsonPrimitive("city")) }
    }

    @Test
    fun enabledServerToolsBecomeLocalToolsWithFlattenedSchema() = runBlocking {
        val model = addServer()
        enqueueListTools(
            """[{"name":"get_weather","description":"Погода","inputSchema":""" +
                """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}]"""
        )

        val definitions = provider().toolDefinitions()

        assertEquals(1, definitions.size)
        val tool = definitions.single()
        assertEquals(McpServerRules.qualifiedToolName(model, "get_weather"), tool.name)
        assertTrue(tool.name.startsWith("mcp_"))
        assertTrue(tool.description.contains("Погода"))
        assertTrue("the JSON-object hint must be present: ${tool.description}", tool.description.contains("JSON"))
        val props = tool.parameters["properties"]!!.jsonObject
        assertEquals("string", props["city"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val required = (tool.parameters["required"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(listOf("city"), required)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun toolListIsCachedUntilTheTtlExpires() = runBlocking {
        addServer()
        enqueueListTools("""[{"name":"ping","description":"p"}]""")
        val provider = provider()

        provider.toolDefinitions()
        assertEquals(3, server.requestCount)

        // A second read within the TTL must not reconnect.
        provider.toolDefinitions()
        assertEquals(3, server.requestCount)

        // Past the TTL the provider reconnects.
        clock.now += 5L * 60L * 1000L + 1L
        enqueueListTools("""[{"name":"ping","description":"p"},{"name":"pong","description":"q"}]""")
        val refreshed = provider.toolDefinitions()
        assertEquals(6, server.requestCount)
        assertEquals(2, refreshed.size)
    }

    @Test
    fun unreachableServerIsSkippedHonestly() = runBlocking {
        val model = McpServer(
            id = "dead",
            name = "dead",
            url = "http://127.0.0.1:1/mcp",
            transport = McpTransport.STREAMABLE_HTTP,
        )
        store.put(null, "dead", model.url, McpTransport.STREAMABLE_HTTP)

        val definitions = provider().toolDefinitions()

        assertTrue("an unreachable server contributes no tools", definitions.isEmpty())
    }

    @Test
    fun disabledServerIsNotContacted() = runBlocking {
        val model = addServer()
        store.setEnabled(model.id, false)

        val definitions = provider().toolDefinitions()

        assertTrue(definitions.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun executeAssemblesArgumentsAndReturnsTheTextResult() = runBlocking {
        val model = addServer()
        val provider = provider()
        provider.seed(
            model,
            listOf(McpToolInfo("echo", "Эхо", weatherSchema)),
        )
        // Seed makes the definitions available without any network round-trip.
        val tool = provider.toolDefinitions().single()
        assertEquals(0, server.requestCount)

        server.enqueue(json(initResult))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            json("""{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"ok:Москва"}]}}""")
        )

        val result = provider.execute(
            tool.name,
            buildJsonObject {
                put("city", "Москва")
                put("days", "3")
                put("options", """{"units":"c"}""")
            }
        )

        assertTrue(result!!.success)
        assertEquals("ok:Москва", result.output)
        val init = server.takeRequest()
        val notify = server.takeRequest()
        val call = server.takeRequest()
        assertEquals("POST", init.method)
        assertTrue(notify.body.readUtf8().contains("notifications/initialized"))
        val body = call.body.readUtf8()
        assertTrue("plain string must stay a string", body.contains("\"city\":\"Москва\""))
        assertTrue("integer text must become a number", body.contains("\"days\":3"))
        assertTrue("object text must become an object", body.contains("\"options\":{\"units\":\"c\"}"))
        assertTrue(body.contains("\"name\":\"echo\""))
    }

    @Test
    fun executeOnAnUnknownMcpNameIsAnHonestError() = runBlocking {
        val result = provider().execute("mcp_ghost_tool", buildJsonObject { })
        assertFalse(result!!.success)
        assertTrue(result.output.contains("недоступен"))
    }

    @Test
    fun executeOnANonMcpNameReturnsNull() = runBlocking {
        assertEquals(null, provider().execute("files_list", buildJsonObject { }))
    }

    @Test
    fun remoteIsErrorIsReportedAsAFailure() = runBlocking {
        val model = addServer()
        val provider = provider()
        provider.seed(model, listOf(McpToolInfo("boom", "b", null)))
        val tool = provider.toolDefinitions().single()

        server.enqueue(json(initResult))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            json("""{"jsonrpc":"2.0","id":2,"result":{"isError":true,"content":[{"type":"text","text":"bad input"}]}}""")
        )

        val result = provider.execute(tool.name, buildJsonObject { put("x", "1") })
        assertFalse(result!!.success)
        assertTrue(result.output.contains("bad input"))
    }
}
