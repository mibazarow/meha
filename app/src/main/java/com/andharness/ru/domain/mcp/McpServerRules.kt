package com.andharness.ru.domain.mcp

import com.andharness.ru.domain.formatting.collapseWhitespace
import java.net.URI
import java.util.Locale

/**
 * Pure validation, normalization and rendering rules for MCP servers — no Android APIs, so every
 * rule is unit-tested on the JVM (`McpServerRulesTest`).
 *
 * Validation is intentionally strict and honest: the `mcp_server_add` tool must say exactly what is
 * wrong with a name/URL/header instead of storing a config that would fail only at connect time.
 */
object McpServerRules {

    const val MAX_NAME_CHARS = 60
    const val MAX_URL_CHARS = 512
    const val MAX_HEADER_NAME_CHARS = 80
    const val MAX_BLOCK_CHARS = 900

    /** The client identifies itself with this name in `initialize.clientInfo.name`. */
    const val CLIENT_NAME = "meha"

    /** HTTP token characters allowed in a header name (RFC 9110 `token`). */
    private val HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")

    /**
     * Headers a user-supplied auth header must never shadow, or the JSON-RPC request itself would
     * break (entity/hop-by-hop framing headers). Compared case-insensitively.
     */
    private val RESERVED_HEADERS = setOf(
        "host", "content-length", "content-type", "accept", "accept-encoding",
        "connection", "transfer-encoding", "user-agent"
    )

    /** Trims and collapses internal whitespace; `null` when blank or longer than [MAX_NAME_CHARS]. */
    fun normalizeName(raw: String?): String? {
        val clean = raw?.let { collapseWhitespace(it) }
        if (clean.isNullOrEmpty()) return null
        if (clean.length > MAX_NAME_CHARS) return null
        return clean
    }

    /**
     * Normalizes a server URL to an absolute `http(s)://host[/path]` form. A missing scheme is
     * assumed to be HTTPS (the safe default for a remote server); any other scheme, a missing host
     * or over-long input yields `null` so the caller can report an honest error.
     */
    fun normalizeUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_CHARS) return null
        if (trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host
        if (host.isNullOrBlank()) return null
        if (uri.fragment != null) return null
        return uri.toASCIIString()
    }

    /**
     * Validates a custom auth header name (e.g. `Authorization`, `X-Api-Key`). `null` for blank,
     * malformed or reserved names, so the caller can report an honest error instead of silently
     * sending a broken header.
     */
    fun normalizeHeaderName(raw: String?): String? {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty() || clean.length > MAX_HEADER_NAME_CHARS) return null
        if (!HEADER_NAME.matches(clean)) return null
        if (clean.lowercase(Locale.ROOT) in RESERVED_HEADERS) return null
        return clean
    }

    /**
     * Makes a remote tool name safe to expose as a local agent tool name: lowercase ASCII, every
     * other character becomes `_`, repeats of `_` collapse, and the result is trimmed to
     * [MAX_SANITIZED_CHARS]. Never returns an empty string (falls back to `tool`).
     */
    fun sanitizeToolName(raw: String): String {
        val lowered = raw.lowercase(Locale.ROOT)
        val mapped = buildString(lowered.length) {
            for (ch in lowered) append(if (ch in 'a'..'z' || ch in '0'..'9' || ch == '_') ch else '_')
        }
        val collapsed = mapped.replace(Regex("_+"), "_").trim('_')
        val bounded = collapsed.take(MAX_SANITIZED_CHARS)
        return bounded.ifEmpty { "tool" }
    }

    /**
     * The stable local name a remote tool will get once dynamic exposure is wired
     * ([com.andharness.ru.domain.mcp.McpToolInfo] on [McpServer]): `mcp_<server>_<tool>`, fully
     * sanitized and bounded. Pure, so the mapping is unit-tested.
     */
    fun qualifiedToolName(server: McpServer, toolName: String): String {
        val key = sanitizeToolName(server.id.ifBlank { server.name })
        val tool = sanitizeToolName(toolName)
        return "mcp_${key}_$tool".take(MAX_QUALIFIED_CHARS).trimEnd('_')
    }

    /**
     * The short prompt-context block. An empty registry still renders a one-line capability hint so
     * the agent always knows MCP servers can be managed from chat; a non-empty registry lists each
     * server with its URL, transport and enabled state, bounded to [MAX_BLOCK_CHARS].
     */
    fun renderBlock(servers: List<McpServer>, limit: Int = MAX_BLOCK_CHARS): String {
        val header =
            "MCP: внешние MCP-серверы можно добавлять, смотреть, проверять и удалять прямо из чата " +
                "(mcp_server_add / mcp_server_list / mcp_server_test / mcp_server_remove) — отдельный " +
                "экран настроек для этого не нужен; инструменты сервера появятся после успешного подключения. " +
                "Инструменты каждого включённого сервера доступны как mcp_<сервер>_<инструмент> " +
                "(например, mcp_pogoda_get_forecast) и вызываются обычным образом."
        if (servers.isEmpty()) return header.take(limit)
        val lines = servers.joinToString("\n") { server ->
            val state = if (server.enabled) "включён" else "выключен"
            val auth = if (server.authHeaderName.isNullOrBlank()) "" else ", auth: ${server.authHeaderName}"
            "- ${server.name} — ${server.url} (${server.transport.name.lowercase(Locale.ROOT)}, $state$auth)"
        }
        return buildString {
            appendLine(header)
            append("Настроенные MCP-серверы (${servers.size}):")
            appendLine()
            append(lines)
        }.take(limit).trim()
    }

    /** Per-name cap used by [sanitizeToolName]. */
    private const val MAX_SANITIZED_CHARS = 48

    /** Total cap used by [qualifiedToolName]. */
    private const val MAX_QUALIFIED_CHARS = 64
}
