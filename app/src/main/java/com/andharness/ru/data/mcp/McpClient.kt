package com.andharness.ru.data.mcp

import com.andharness.ru.domain.mcp.McpServer
import com.andharness.ru.domain.mcp.McpServerInfo
import com.andharness.ru.domain.mcp.McpServerRules
import com.andharness.ru.domain.mcp.McpToolCallResult
import com.andharness.ru.domain.mcp.McpToolInfo
import com.andharness.ru.domain.mcp.McpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.Closeable
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/**
 * A JSON-RPC 2.0 MCP client over OkHttp, implementing both HTTP transports.
 *
 * **Streamable HTTP** (protocol 2025-03-26+): every message is one POST to the server URL with
 * `Accept: application/json, text/event-stream`; the reply is either a JSON body or a request-scoped
 * SSE stream, and a returned `Mcp-Session-Id` header is carried on all later requests together with
 * `MCP-Protocol-Version`.
 *
 * **HTTP+SSE** (legacy 2024-11-05, deprecated): a long-lived `GET text/event-stream` whose first
 * `endpoint` event advertises the POST URL, after which responses arrive as `message` events.
 *
 * `AUTO` follows the spec's backward-compatibility probe: POST first, and only on an HTTP
 * 400/404/405 whose body is not a JSON-RPC error fall back to the legacy GET handshake.
 *
 * Every method opens a session, runs the `initialize` handshake plus `notifications/initialized`,
 * performs the operation, and closes — so no connection state leaks between calls and a later
 * dynamic-exposure task can reuse [listTools]/[callTool] as-is.
 *
 * Timeouts are bounded (connect 15 s, read/write 30 s) instead of the agent's long LLM read budget.
 * All failures are reported honestly (bad URL/DNS, HTTP status, malformed JSON-RPC, missing
 * session/endpoint, timeout) as [McpClientException] messages, never as fabricated success.
 *
 * References:
 * - Transports — https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
 * - Lifecycle — https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle
 * - Tools — https://modelcontextprotocol.io/specification/2025-06-18/server/tools
 * - SSE — https://html.spec.whatwg.org/multipage/server-sent-events.html
 * - OkHttp — https://square.github.io/okhttp/
 */
class McpClient(private val http: OkHttpClient = defaultMcpHttpClient()) {

    /** `initialize` + `notifications/initialized` + a paginated `tools/list`. */
    suspend fun test(server: McpServer, token: String?): McpConnectResult = withContext(Dispatchers.IO) {
        try {
            val opened = openSession(server, token)
            try {
                McpConnectResult.Ok(opened.serverInfo, opened.transport, collectTools(opened.session))
            } finally {
                opened.session.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            McpConnectResult.Error(honestMessage(e))
        }
    }

    /** Opens a session, handshakes and returns every tool the server advertises. */
    suspend fun listTools(server: McpServer, token: String?): McpToolsResult = withContext(Dispatchers.IO) {
        try {
            val opened = openSession(server, token)
            try {
                McpToolsResult.Ok(collectTools(opened.session))
            } finally {
                opened.session.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            McpToolsResult.Error(honestMessage(e))
        }
    }

    /** Opens a session, handshakes and invokes one remote tool. */
    suspend fun callTool(
        server: McpServer,
        token: String?,
        toolName: String,
        arguments: JsonObject,
    ): McpCallOutcome = withContext(Dispatchers.IO) {
        try {
            val opened = openSession(server, token)
            try {
                val params = buildJsonObject {
                    put("name", toolName)
                    put("arguments", arguments)
                }
                when (val response = opened.session.request("tools/call", params)) {
                    is McpRpcResponse.Success -> McpCallOutcome.Ok(parseCallResult(response.result))
                    is McpRpcResponse.Failure -> McpCallOutcome.Error(
                        "MCP «${server.name}»: вызов $toolName вернул ошибку ${response.code}: ${response.message}"
                    )
                    is McpRpcResponse.Malformed -> McpCallOutcome.Error(
                        "MCP «${server.name}»: вызов $toolName — некорректный ответ (${response.reason})."
                    )
                }
            } finally {
                opened.session.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            McpCallOutcome.Error(honestMessage(e))
        }
    }

    /** Opens a session for the resolved transport and completes the initialize handshake. */
    private fun openSession(server: McpServer, token: String?): Opened =
        when (server.transport) {
            McpTransport.SSE -> openSse(server, token)
            McpTransport.STREAMABLE_HTTP -> openStreamable(server, token)
            McpTransport.AUTO -> try {
                openStreamable(server, token)
            } catch (e: McpClientException) {
                // Spec backward-compat probe: only a 400/404/405 that is not a JSON-RPC error means
                // "this is probably an old HTTP+SSE server"; a genuine protocol error is rethrown.
                if (e.httpCode == 400 || e.httpCode == 404 || e.httpCode == 405) {
                    openSse(server, token)
                } else {
                    throw e
                }
            }
        }

    private fun openStreamable(server: McpServer, token: String?): Opened {
        val session = StreamableHttpSession(http, server.url, server.authHeaderName, token)
        val info = initialize(session, server)
        return Opened(session, info, McpTransport.STREAMABLE_HTTP)
    }

    private fun openSse(server: McpServer, token: String?): Opened {
        val request = Request.Builder()
            .url(server.url)
            .get()
            .header("Accept", "text/event-stream")
            .apply { applyMcpAuth(server.authHeaderName, token) }
            .build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val text = response.body?.string().orEmpty()
            val code = response.code
            response.close()
            throw McpClientException("MCP «${server.name}»: сервер вернул HTTP $code${snippet(text)}", httpCode = code)
        }
        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/event-stream", ignoreCase = true)) {
            response.close()
            throw McpClientException(
                "MCP «${server.name}»: GET не вернул text/event-stream (получено «$contentType»)."
            )
        }
        val source = response.body?.source()
        if (source == null) {
            response.close()
            throw McpClientException("MCP «${server.name}»: пустое тело SSE-потока.")
        }
        val endpoint = try {
            readEndpointEvent(server, source)
        } catch (e: Exception) {
            response.close()
            throw e
        }
        val session = SseSession(http, endpoint, server.authHeaderName, token, response, source)
        return try {
            Opened(session, initialize(session, server), McpTransport.SSE)
        } catch (e: Exception) {
            session.close()
            throw e
        }
    }

    /** Waits for the legacy transport's first `endpoint` event (bounded), then resolves its URL. */
    private fun readEndpointEvent(server: McpServer, source: BufferedSource): String {
        val accumulator = McpSseAccumulator()
        val deadline = System.nanoTime() + ENDPOINT_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val line = source.readUtf8Line() ?: break
            val event = accumulator.line(line) ?: continue
            if (event.event == McpSseEvent.ENDPOINT || event.data.contains("://") || event.data.startsWith("/")) {
                val base = server.url.toHttpUrlOrNull()
                    ?: throw McpClientException("MCP «${server.name}»: некорректный URL.")
                return base.resolve(event.data.trim())?.toString()
                    ?: throw McpClientException("MCP «${server.name}»: не удалось разобрать endpoint SSE.")
            }
        }
        throw McpClientException(
            "MCP «${server.name}»: сервер не прислал событие endpoint за ${ENDPOINT_TIMEOUT_SEC} с."
        )
    }

    /** `initialize` handshake + `notifications/initialized`, returning the server's self-description. */
    private fun initialize(session: Session, server: McpServer): McpServerInfo {
        val params = buildJsonObject {
            put("protocolVersion", McpJsonRpc.PROTOCOL_VERSION)
            putJsonObject("capabilities") { }
            putJsonObject("clientInfo") {
                put("name", McpServerRules.CLIENT_NAME)
                put("version", CLIENT_VERSION)
            }
        }
        val response = session.request("initialize", params)
        val info = when (response) {
            is McpRpcResponse.Success -> parseServerInfo(response.result)
            is McpRpcResponse.Failure -> throw McpClientException(
                "MCP «${server.name}»: initialize вернул ошибку ${response.code}: ${response.message}"
            )
            is McpRpcResponse.Malformed -> throw McpClientException(
                "MCP «${server.name}»: initialize — некорректный ответ (${response.reason})."
            )
        }
        // The server MUST NOT respond to this notification; failing to send it is a hard error.
        session.notify("notifications/initialized", null)
        return info
    }

    private fun parseServerInfo(result: JsonElement): McpServerInfo {
        val obj = result as? JsonObject
            ?: return McpServerInfo("неизвестный сервер", "?", McpJsonRpc.PROTOCOL_VERSION)
        val serverInfo = obj["serverInfo"] as? JsonObject
        val name = (serverInfo?.get("name") as? JsonPrimitive)?.contentOrNull ?: "неизвестный сервер"
        val version = (serverInfo?.get("version") as? JsonPrimitive)?.contentOrNull ?: "?"
        val protocol = (obj["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: McpJsonRpc.PROTOCOL_VERSION
        return McpServerInfo(name, version, protocol)
    }

    /** Paginated `tools/list` via `nextCursor`, bounded to [MAX_PAGES] pages. */
    private fun collectTools(session: Session): List<McpToolInfo> {
        val all = mutableListOf<McpToolInfo>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = cursor?.let { value -> buildJsonObject { put("cursor", value) } }
            val response = session.request("tools/list", params)
            val result = when (response) {
                is McpRpcResponse.Success -> response.result
                is McpRpcResponse.Failure -> throw McpClientException(
                    "MCP tools/list: сервер вернул ошибку ${response.code}: ${response.message}"
                )
                is McpRpcResponse.Malformed -> throw McpClientException(
                    "MCP tools/list: некорректный ответ (${response.reason})."
                )
            }
            val (tools, next) = parseTools(result)
            all += tools
            cursor = next
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        return all
    }

    private fun parseTools(result: JsonElement): Pair<List<McpToolInfo>, String?> {
        val obj = result as? JsonObject ?: throw McpClientException("MCP tools/list: ожидался JSON-объект.")
        val tools = (obj["tools"] as? JsonArray).orEmpty().mapNotNull { element ->
            val tool = element as? JsonObject ?: return@mapNotNull null
            val name = (tool["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val description = (tool["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            // `inputSchema` is optional per spec; keep it only when it really is an object.
            val inputSchema = tool["inputSchema"] as? JsonObject
            McpToolInfo(name, description, inputSchema)
        }
        val next = (obj["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return tools to next
    }

    private fun parseCallResult(result: JsonElement): McpToolCallResult {
        val obj = result as? JsonObject ?: throw McpClientException("MCP tools/call: ожидался JSON-объект.")
        val isError = (obj["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        val items = (obj["content"] as? JsonArray).orEmpty()
        val texts = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if ((item["type"] as? JsonPrimitive)?.contentOrNull != "text") return@mapNotNull null
            (item["text"] as? JsonPrimitive)?.contentOrNull
        }
        val text = texts.joinToString("\n")
        val nonText = (items.size - texts.size).coerceAtLeast(0)
        val safeText = text.ifBlank {
            when {
                isError -> "Сервер вернул ошибку без текста."
                nonText > 0 ->
                    "Сервер вернул только нетекстовые данные ($nonText частей: изображения/ресурсы) — " +
                        "текстового результата нет."
                else -> "Сервер не вернул текстового результата."
            }
        }
        return McpToolCallResult(safeText, isError)
    }

    /** A session that can issue one JSON-RPC request and send notifications. */
    private interface Session : Closeable {
        fun request(method: String, params: JsonObject?): McpRpcResponse
        fun notify(method: String, params: JsonObject?)
    }

    /** A resolved, initialized session plus the server info and transport that produced it. */
    private data class Opened(
        val session: Session,
        val serverInfo: McpServerInfo,
        val transport: McpTransport,
    )

    /** Streamable HTTP: one POST per message; JSON or request-scoped SSE reply. */
    private class StreamableHttpSession(
        private val http: OkHttpClient,
        private val baseUrl: String,
        private val authHeaderName: String?,
        private val token: String?,
    ) : Session {

        private val mediaJson = "application/json".toMediaType()
        private val ids = AtomicLong(0)
        private var sessionId: String? = null
        private var handshakeDone = false

        override fun request(method: String, params: JsonObject?): McpRpcResponse {
            val id = ids.incrementAndGet()
            post(McpJsonRpc.requestJson(id, method, params)).use { resp ->
                val body = readBodyOrThrow(resp)
                handshakeDone = true
                captureSession(resp)
                val contentType = resp.header("Content-Type").orEmpty()
                return if (contentType.contains("text/event-stream", ignoreCase = true)) {
                    findSseResponse(body, id)
                } else {
                    McpJsonRpc.parseResponse(body)
                }
            }
        }

        override fun notify(method: String, params: JsonObject?) {
            post(McpJsonRpc.notificationJson(method, params)).use { resp ->
                readBodyOrThrow(resp)
                handshakeDone = true
                captureSession(resp)
            }
        }

        override fun close() = Unit

        private fun captureSession(resp: Response) {
            resp.header(SESSION_HEADER)?.takeIf { it.isNotBlank() }?.let { sessionId = it }
        }

        private fun findSseResponse(body: String, id: Long): McpRpcResponse {
            val messages = McpSseParser.parseAll(body)
                .filter { it.event == McpSseEvent.MESSAGE }
                .map { McpJsonRpc.parseResponse(it.data) }
            messages.firstOrNull { it is McpRpcResponse.Success && it.id == id }?.let { return it }
            messages.firstOrNull { it is McpRpcResponse.Failure && (it.id == null || it.id == id) }?.let { return it }
            return McpRpcResponse.Malformed("в SSE-потоке нет ответа с id=$id")
        }

        private fun post(body: String): Response {
            val builder = Request.Builder()
                .url(baseUrl)
                .post(body.toRequestBody(mediaJson))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .apply { applyMcpAuth(authHeaderName, token) }
            if (handshakeDone) builder.header("MCP-Protocol-Version", McpJsonRpc.PROTOCOL_VERSION)
            sessionId?.let { builder.header(SESSION_HEADER, it) }
            return http.newCall(builder.build()).execute()
        }

        private fun readBodyOrThrow(resp: Response): String {
            val code = resp.code
            val text = resp.body?.string().orEmpty()
            if (code !in 200..299) {
                val parsed = McpJsonRpc.parseResponse(text)
                if (parsed is McpRpcResponse.Failure) {
                    throw McpClientException("MCP: сервер вернул ошибку ${parsed.code}: ${parsed.message}")
                }
                throw McpClientException("MCP: сервер вернул HTTP $code${snippet(text)}", httpCode = code)
            }
            return text
        }
    }

    /** Legacy HTTP+SSE: a live GET stream plus POSTs to the advertised endpoint. */
    private class SseSession(
        private val http: OkHttpClient,
        private val endpointUrl: String,
        private val authHeaderName: String?,
        private val token: String?,
        private val stream: Response,
        private val source: BufferedSource,
    ) : Session {

        private val mediaJson = "application/json".toMediaType()
        private val ids = AtomicLong(0)
        private val accumulator = McpSseAccumulator()

        override fun request(method: String, params: JsonObject?): McpRpcResponse {
            val id = ids.incrementAndGet()
            postToEndpoint(McpJsonRpc.requestJson(id, method, params))
            val deadline = System.nanoTime() + SSE_WAIT_TIMEOUT_NANOS
            while (System.nanoTime() < deadline) {
                val line = source.readUtf8Line()
                    ?: throw McpClientException("MCP: SSE-поток закрыт до ответа на $method.")
                val event = accumulator.line(line) ?: continue
                if (event.event != McpSseEvent.MESSAGE) continue
                when (val parsed = McpJsonRpc.parseResponse(event.data)) {
                    is McpRpcResponse.Success -> if (parsed.id == id) return parsed
                    is McpRpcResponse.Failure -> if (parsed.id == null || parsed.id == id) return parsed
                    is McpRpcResponse.Malformed -> return parsed
                }
            }
            throw McpClientException("MCP: таймаут ожидания ответа на $method по SSE.")
        }

        override fun notify(method: String, params: JsonObject?) {
            postToEndpoint(McpJsonRpc.notificationJson(method, params))
        }

        override fun close() {
            runCatching { source.close() }
            runCatching { stream.close() }
        }

        private fun postToEndpoint(body: String) {
            val builder = Request.Builder()
                .url(endpointUrl)
                .post(body.toRequestBody(mediaJson))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .apply { applyMcpAuth(authHeaderName, token) }
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    throw McpClientException(
                        "MCP: POST на endpoint вернул HTTP ${response.code}${snippet(text)}"
                    )
                }
                // Drain a bounded body so the stream does not linger.
                response.body?.string()
            }
        }
    }

    private companion object {

        /** How long to wait for the legacy transport's `endpoint` event. */
        const val ENDPOINT_TIMEOUT_SEC = 15L
        val ENDPOINT_TIMEOUT_NANOS: Long = TimeUnit.SECONDS.toNanos(ENDPOINT_TIMEOUT_SEC)

        /** How long to wait for a response on an established legacy SSE stream. */
        val SSE_WAIT_TIMEOUT_NANOS: Long = TimeUnit.SECONDS.toNanos(30)

        /** Hard cap on `tools/list` pages, so a buggy `nextCursor` loop cannot run forever. */
        const val MAX_PAGES = 20

        /** Session header assigned during Streamable HTTP initialization. */
        const val SESSION_HEADER = "Mcp-Session-Id"

        /** Client version reported in `initialize.clientInfo.version`. */
        const val CLIENT_VERSION = "1.0"
    }
}

/**
 * Bounded socket budgets for the MCP transport: connect 15 s, read/write 30 s; the call timeout is
 * disabled so a long-lived legacy SSE GET is not killed mid-stream (each read is still bounded).
 */
internal fun defaultMcpHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()

/** Adds the optional custom auth header; a bare `Authorization` token gets the `Bearer` scheme. */
private fun Request.Builder.applyMcpAuth(headerName: String?, token: String?) {
    if (headerName.isNullOrBlank() || token.isNullOrBlank()) return
    val value = if (headerName.equals("Authorization", ignoreCase = true) && !token.contains(' ')) {
        "Bearer $token"
    } else {
        token
    }
    header(headerName, value)
}

/** `HTTP 404` plus a trimmed body snippet, or just the status when the body is empty. */
private fun snippet(body: String): String {
    val clean = body.trim().replace('\n', ' ').take(ERROR_SNIPPET_CHARS)
    return if (clean.isEmpty()) "" else " — $clean"
}

/** Bounded prefix of an HTTP error body echoed into an honest message. */
private const val ERROR_SNIPPET_CHARS = 160

/** Honest, user-facing mapping of a transport exception. Never fabricates success. */
internal fun honestMessage(e: Exception): String = when (e) {
    is McpClientException -> e.message ?: "MCP: ошибка клиента."
    is UnknownHostException -> "MCP: не удалось разрешить адрес сервера (${e.message ?: "DNS"})."
    is SocketTimeoutException -> "MCP: таймаут связи с сервером."
    is ConnectException -> "MCP: сервер недоступен (${e.message ?: "соединение отклонено"})."
    is SSLException -> "MCP: ошибка TLS (${e.message ?: "сертификат"})."
    else -> "MCP: ошибка связи (${e::class.java.simpleName}: ${e.message})."
}
