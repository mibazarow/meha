package com.andharness.ru.data.mcp

import com.andharness.ru.domain.serialization.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Pure JSON-RPC 2.0 encode/decode for MCP, with no Android or OkHttp dependency — so the wire
 * format is exercised on the JVM (`McpJsonRpcTest`).
 *
 * Requests carry an `id`; notifications (e.g. `notifications/initialized`) MUST NOT. All MCP
 * messages MUST follow JSON-RPC 2.0 and be UTF-8 encoded.
 *
 * References:
 * - https://modelcontextprotocol.io/specification/2025-06-18/basic (Messages)
 * - https://www.jsonrpc.org/specification
 * - https://kotlinlang.org/api/kotlinx.serialization/
 */
object McpJsonRpc {

    /**
     * Protocol revision this client speaks and sends in `initialize.params.protocolVersion` and in
     * the `MCP-Protocol-Version` header of subsequent requests.
     *
     * Reference: https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle
     */
    const val PROTOCOL_VERSION = "2025-06-18"

    private val json = AppJson.LenientEncodeDefaults

    /** A JSON-RPC request (with an `id`) as a compact JSON string. */
    fun requestJson(id: Long, method: String, params: JsonObject? = null): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    /** A JSON-RPC notification (no `id` — the receiver MUST NOT respond) as a compact JSON string. */
    fun notificationJson(method: String, params: JsonObject? = null): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    /** Parses one JSON-RPC message body (from a plain HTTP body or an SSE `message` event). */
    fun parseResponse(body: String): McpRpcResponse {
        val obj: JsonObject = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return McpRpcResponse.Malformed("не JSON: ${e.message ?: body.take(80)}")
        }
        val version = (obj["jsonrpc"] as? JsonPrimitive)?.contentOrNull
        if (version != "2.0") {
            return McpRpcResponse.Malformed("отсутствует jsonrpc=\"2.0\"")
        }
        val id = (obj["id"] as? JsonPrimitive)?.longOrNull
        val error = obj["error"] as? JsonObject
        if (error != null) {
            val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: -1
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "без описания"
            return McpRpcResponse.Failure(id, code, message)
        }
        val result = obj["result"]
            ?: return McpRpcResponse.Malformed("нет ни result, ни error")
        return McpRpcResponse.Success(id, result)
    }
}
