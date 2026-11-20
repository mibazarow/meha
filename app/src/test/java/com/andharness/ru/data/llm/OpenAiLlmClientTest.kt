package com.andharness.ru.data.llm

import com.andharness.ru.domain.agent.LlmContentPart
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class OpenAiLlmClientTest {

    private val sseStream = """
        data: {"id":"1","choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}

        data: {"id":"1","choices":[{"delta":{"content":"При"},"finish_reason":null}]}

        data: {"id":"1","choices":[{"delta":{"content":"вет"},"finish_reason":null}]}

        data: {"id":"1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"files_list","arguments":""}}]},"finish_reason":null}]}

        data: {"id":"1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]},"finish_reason":null}]}

        data: {"id":"1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"\/\"}"}}]},"finish_reason":null}]}

        data: {"id":"1","choices":[{"delta":{},"finish_reason":"tool_calls"}]}

        data: [DONE]
    """.trimIndent()

    class FakeLlmSettingsStore(
        override val config: Flow<LlmSettings>
    ) : LlmSettingsStore {
        override suspend fun updateEndpoint(value: String) = TODO("unused")
        override suspend fun updateModel(value: String) = TODO("unused")
        override suspend fun updateReasoningEffort(value: String) = TODO("unused")
        override suspend fun updateContextCompressionTokens(value: Long) = TODO("unused")
        override suspend fun getApiKey(): String? = null
        override suspend fun setApiKey(key: String) = TODO("unused")
        override suspend fun clearApiKey() = TODO("unused")
        override suspend fun getAuthConfig(): com.andharness.ru.domain.llm.LlmAuthConfig =
            com.andharness.ru.domain.llm.LlmAuthConfig.DEFAULT
        override suspend fun setAuthConfig(config: com.andharness.ru.domain.llm.LlmAuthConfig) = TODO("unused")
        override suspend fun reset() = TODO("unused")
        override fun isConfigured(): Flow<Boolean> = flowOf(true)
    }

    @Test
    fun streamsTextAndConcatenatesToolCalls() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setBody(sseStream)
                .addHeader("Content-Type", "text/event-stream")
        )

        try {
            val settings = LlmSettings(
                endpoint = server.url("/v1").toString(),
                model = "test-model"
            )
            val settingsStore = FakeLlmSettingsStore(config = flowOf(settings))
            val okHttp = OkHttpClient.Builder().build()
            val client = OpenAiLlmClient(settingsStore, okHttp)

            val events = client
                .complete(LlmRequest(messages = listOf(LlmMessage("user", "hi"))))
                .toList()

            assertTrue("text delta 'При' must be present",
                events.contains(LlmEvent.Text("При")))
            assertTrue("text delta 'вет' must be present",
                events.contains(LlmEvent.Text("вет")))

            val toolCallsEvents = events.filterIsInstance<LlmEvent.ToolCalls>()
            assertEquals("exactly one ToolCalls event expected", 1, toolCallsEvents.size)
            val calls = toolCallsEvents[0].calls
            assertEquals("exactly one tool call expected", 1, calls.size)
            assertEquals("files_list", calls[0].name)
            assertEquals("call_1", calls[0].id)
            assertEquals("{\"path\":\"/\"}", calls[0].arguments)

            // A tool-call turn now also terminates with an explicit Done event (emitted when the
            // stream ends), so a caller never has to treat EOF as a successful completion.
        } finally {
            server.shutdown()
        }
    }

    /**
     * Usage usually arrives as a chunk with `choices: []`. It must be read BEFORE the choices guard,
     * otherwise the estimate/actual calibration never sees the provider's real prompt size.
     */
    @Test
    fun emitsProviderUsageEvenWhenTheChunkCarriesNoChoices() = runBlocking {
        val stream = """
            data: {"id":"1","choices":[{"delta":{"content":"ok"},"finish_reason":null}]}

            data: {"id":"1","choices":[],"usage":{"prompt_tokens":1234,"completion_tokens":7,"total_tokens":1241}}

            data: [DONE]
        """.trimIndent()
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(stream).addHeader("Content-Type", "text/event-stream"))

        try {
            val client = OpenAiLlmClient(
                FakeLlmSettingsStore(
                    config = flowOf(LlmSettings(endpoint = server.url("/v1").toString(), model = "test-model"))
                ),
                OkHttpClient.Builder().build(),
            )

            val events = client.complete(LlmRequest(messages = listOf(LlmMessage("user", "hi")))).toList()

            val usage = events.filterIsInstance<LlmEvent.Usage>()
            assertEquals("exactly one usage event expected", 1, usage.size)
            assertEquals(1234L, usage[0].promptTokens)
            assertEquals(7L, usage[0].completionTokens)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun flushesToolCallsAndEmitsDoneWhenProviderEndsWithStop() = runBlocking {
        // Some OpenAI-compatible providers end a tool-call turn with "stop" instead of "tool_calls".
        val stream = """
            data: {"id":"1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"files_list","arguments":"{}"}}]},"finish_reason":null}]}

            data: {"id":"1","choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]
        """.trimIndent()
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(stream).addHeader("Content-Type", "text/event-stream"))

        try {
            val client = OpenAiLlmClient(
                FakeLlmSettingsStore(
                    config = flowOf(LlmSettings(endpoint = server.url("/v1").toString(), model = "test-model"))
                ),
                OkHttpClient.Builder().build(),
            )

            val events = client.complete(LlmRequest(messages = listOf(LlmMessage("user", "hi")))).toList()

            val toolCalls = events.filterIsInstance<LlmEvent.ToolCalls>()
            assertEquals("tool calls must not be lost on a 'stop' finish", 1, toolCalls.size)
            assertEquals("files_list", toolCalls[0].calls[0].name)
            assertEquals("call_9", toolCalls[0].calls[0].id)
            assertTrue("a Done event must terminate the stream", events.contains(LlmEvent.Done))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun flushesToolCallsAndEmitsDoneWhenStreamEndsWithoutTerminalChunk() = runBlocking {
        val stream = """
            data: {"id":"1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"device_info","arguments":"{}"}}]}}]}
        """.trimIndent()
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(stream).addHeader("Content-Type", "text/event-stream"))

        try {
            val client = OpenAiLlmClient(
                FakeLlmSettingsStore(
                    config = flowOf(LlmSettings(endpoint = server.url("/v1").toString(), model = "test-model"))
                ),
                OkHttpClient.Builder().build(),
            )

            val events = client.complete(LlmRequest(messages = listOf(LlmMessage("user", "hi")))).toList()

            assertEquals(1, events.filterIsInstance<LlmEvent.ToolCalls>().size)
            assertTrue("a Done event must terminate the stream", events.contains(LlmEvent.Done))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aMalformedSseLineDoesNotAbortTheStream() = runBlocking {
        val stream = """
            data: {"id":"1","choices":[{"delta":{"content":"A"},"finish_reason":null}]}

            data: {this-is-not-json

            data: {"id":"1","choices":[{"delta":{"content":"B"},"finish_reason":"stop"}]}

            data: [DONE]
        """.trimIndent()
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(stream).addHeader("Content-Type", "text/event-stream"))

        try {
            val client = OpenAiLlmClient(
                FakeLlmSettingsStore(
                    config = flowOf(LlmSettings(endpoint = server.url("/v1").toString(), model = "test-model"))
                ),
                OkHttpClient.Builder().build(),
            )

            val events = client.complete(LlmRequest(messages = listOf(LlmMessage("user", "hi")))).toList()

            assertTrue(events.contains(LlmEvent.Text("A")))
            assertTrue("streaming must continue after a malformed chunk", events.contains(LlmEvent.Text("B")))
            assertTrue(events.contains(LlmEvent.Done))
        } finally {
            server.shutdown()
        }
    }

    /**
     * A stalled SSE stream (the server sends one real delta and then never sends anything again,
     * keeping the socket open) MUST terminate on its own. This is the regression test for the
     * hang: OkHttp's readTimeout counts any byte and would sit there for the whole data-wait budget,
     * and the previous coroutine watchdog could be defeated. The external watchdog now cancels the Call from its own
     * thread after the idle budget, and the flow ends with an honest Error.
     *
     * No real network is used: the "server" is a loopback [ServerSocket] and the budgets are
     * shortened through the test seam, so the whole test finishes in ~1-2 s.
     */
    @Test
    fun stalledStreamIsAbortedByTheExternalWatchdog() = runBlocking {
        val serverSocket = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            try {
                serverSocket.accept().use { socket ->
                    // Drain the request headers so the client's request write cannot block.
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = socket.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Transfer-Encoding: chunked\r\n\r\n"
                            ).toByteArray()
                    )
                    val chunk =
                        "data: {\"id\":\"1\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n".toByteArray()
                    out.write("${Integer.toHexString(chunk.size)}\r\n".toByteArray())
                    out.write(chunk)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    // Stall: never send another chunk and never close the socket.
                    Thread.sleep(20_000)
                }
            } catch (_: Exception) {
                // Socket closed by the test teardown — expected.
            }
        }

        try {
            val store = FakeLlmSettingsStore(
                config = flowOf(
                    LlmSettings(
                        endpoint = "http://127.0.0.1:${serverSocket.localPort}/v1",
                        model = "test-model"
                    )
                )
            )
            val client = OpenAiLlmClient(store, OkHttpClient.Builder().build())
            // Production budgets are 1024 s idle / 60 min cap; shorten them for the test only.
            client.idleProgressTimeoutOverrideSec = 1L
            client.maxCompletionOverrideSec = 30L

            val events = withTimeout(15_000) {
                client.complete(LlmRequest(messages = listOf(LlmMessage("user", "hi")))).toList()
            }

            assertTrue("the real delta before the stall must be delivered", events.contains(LlmEvent.Text("A")))
            val errors = events.filterIsInstance<LlmEvent.Error>()
            assertEquals("a stalled stream must end with exactly one Error", 1, errors.size)
            assertTrue(
                "the error must be the honest idle-watchdog reason, was: ${errors[0].message}",
                errors[0].message.contains("поток завис")
            )
            assertFalse("a stalled stream must not be offered as retryable", errors[0].retryable)
        } finally {
            serverSocket.close()
            serverThread.interrupt()
        }
    }

    /**
     * An immediately-EOF/empty response must terminate right away (no bytes at all is the "empty
     * stream" case) instead of being read forever.
     */
    @Test
    fun emptyStreamTerminatesImmediately() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("")
        )

        try {
            val client = OpenAiLlmClient(
                FakeLlmSettingsStore(
                    config = flowOf(LlmSettings(endpoint = server.url("/v1").toString(), model = "test-model"))
                ),
                OkHttpClient.Builder().build(),
            )

            val events = withTimeout(10_000) {
                client.complete(LlmRequest(messages = listOf(LlmMessage("user", "hi")))).toList()
            }

            assertTrue("an empty stream must still terminate with Done", events.contains(LlmEvent.Done))
        } finally {
            server.shutdown()
        }
    }

    /**
     * Reasoning models stream `reasoning_content` deltas BEFORE the first visible `content` token.
     * Those deltas are real progress and must refresh the idle watchdog: otherwise a model that
     * thinks for longer than the no-progress budget is aborted mid-thought.
     *
     * Regression test: the server sends ONLY reasoning deltas for ~2.4 s (well beyond the 1 s test
     * budget) before the answer. Without the fix the watchdog would fire and the turn would end in
     * an Error; with it, the stream completes and the reasoning text stays invisible to the user.
     * (The companion "silent stream still aborts" case is covered by
     * [stalledStreamIsAbortedByTheExternalWatchdog].)
     */
    @Test
    fun reasoningOnlyStreamDoesNotTripTheIdleWatchdog() = runBlocking {
        val serverSocket = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            try {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = socket.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Transfer-Encoding: chunked\r\n\r\n"
                            ).toByteArray()
                    )

                    fun send(payload: String) {
                        val chunk = "data: $payload\n\n".toByteArray()
                        out.write("${Integer.toHexString(chunk.size)}\r\n".toByteArray())
                        out.write(chunk)
                        out.write("\r\n".toByteArray())
                        out.flush()
                    }

                    // 6 reasoning-only deltas, 400 ms apart: ~2.4 s with no visible content.
                    repeat(6) { index ->
                        send(
                            "{\"id\":\"1\",\"choices\":[{\"delta\":{\"reasoning_content\":\"think$index \"}," +
                                "\"finish_reason\":null}]}"
                        )
                        Thread.sleep(400)
                    }
                    send(
                        "{\"id\":\"1\",\"choices\":[{\"delta\":{\"content\":\"answer\"}," +
                            "\"finish_reason\":\"stop\"}]}"
                    )
                    send("[DONE]")
                }
            } catch (_: Exception) {
                // Socket closed by the test teardown — expected.
            }
        }

        try {
            val store = FakeLlmSettingsStore(
                config = flowOf(
                    LlmSettings(
                        endpoint = "http://127.0.0.1:${serverSocket.localPort}/v1",
                        model = "test-model"
                    )
                )
            )
            val client = OpenAiLlmClient(store, OkHttpClient.Builder().build())
            // Only 1 s of silence is tolerated in this test.
            client.idleProgressTimeoutOverrideSec = 1L
            client.maxCompletionOverrideSec = 30L

            val events = withTimeout(20_000) {
                client.complete(LlmRequest(messages = listOf(LlmMessage("user", "hi")))).toList()
            }

            assertTrue(
                "a reasoning-only turn must not be aborted: " +
                    events.filterIsInstance<LlmEvent.Error>().map { it.message },
                events.none { it is LlmEvent.Error }
            )
            assertTrue("the visible answer must still arrive", events.contains(LlmEvent.Text("answer")))
            assertTrue(
                "reasoning deltas must never be shown as user text",
                events.filterIsInstance<LlmEvent.Text>().none { it.delta.contains("think") }
            )
        } finally {
            serverSocket.close()
            serverThread.interrupt()
        }
    }

    /**
     * Locks the transport shape the orchestrator relies on: a message carrying [LlmContentPart]s
     * must serialise `content` as an ARRAY (`text` + `image_url` parts, the image as an inline data
     * URI), while a plain message keeps `content` as a JSON string. Reference for the `image_url`
     * part: https://platform.openai.com/docs/guides/vision
     */
    @Test
    fun multimodalContentPartsSerializeAsArrayWhilePlainTextStaysAString() = runBlocking {
        val stream = """
            data: {"id":"1","choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

            data: [DONE]
        """.trimIndent()
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(stream).addHeader("Content-Type", "text/event-stream"))

        try {
            val client = OpenAiLlmClient(
                FakeLlmSettingsStore(
                    config = flowOf(LlmSettings(endpoint = server.url("/v1").toString(), model = "test-model"))
                ),
                OkHttpClient.Builder().build(),
            )

            client.complete(
                LlmRequest(
                    messages = listOf(
                        LlmMessage(
                            role = "user",
                            contentParts = listOf(
                                LlmContentPart.Text("hi"),
                                LlmContentPart.ImageUrl("data:image/jpeg;base64,AAAA")
                            )
                        ),
                        LlmMessage(role = "user", content = "plain-text")
                    )
                )
            ).toList()

            val body = server.takeRequest().body.readUtf8()
            assertTrue(
                "the multimodal message must inline the image as an image_url part",
                body.contains("\"type\":\"image_url\"")
            )
            assertTrue(
                "the image_url part must carry the base64 data URI verbatim",
                body.contains("\"url\":\"data:image/jpeg;base64,AAAA\"")
            )
            assertTrue(
                "the text part must travel in the same content array",
                body.contains("\"content\":[{\"type\":\"text\",\"text\":\"hi\"}")
            )
            assertTrue(
                "a plain message must still serialise content as a string",
                body.contains("\"content\":\"plain-text\"")
            )
        } finally {
            server.shutdown()
        }
    }
}
