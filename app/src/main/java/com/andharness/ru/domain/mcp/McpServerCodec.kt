package com.andharness.ru.domain.mcp

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure encode/decode/validation logic for the MCP server registry. Depends only on
 * kotlinx.serialization, so it is exercised on the JVM (see `McpServerStoreTest` /
 * `McpServerCodecTest`); the Android-free store [com.andharness.ru.data.mcp.McpServerStore] only
 * adds file I/O on top.
 *
 * The persisted blob is a top-level JSON array of [McpServer]. It never contains a token: the model
 * has no field for one (see [McpServer] and [com.andharness.ru.data.mcp.McpSecretStore]).
 *
 * kotlinx.serialization Json reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
object McpServerCodec {

    /** Hard cap on stored servers; adding past it is rejected (never silently drops user config). */
    const val MAX_SERVERS = 20

    private val json = AppJson.LenientEncodeDefaults

    fun encodeServers(servers: List<McpServer>): String = json.encodeToString(servers)

    /**
     * Decodes the persisted server list. A corrupt/foreign/missing blob degrades to an empty list
     * (same policy as `SkillCodec`); records without an id/name/url are dropped, the transport is
     * re-parsed leniently, and the list is capped to [MAX_SERVERS] so a hand-edited file can never
     * break the tools.
     */
    fun decodeServers(raw: String?): List<McpServer> =
        json.decodeOrNull<List<McpServer>>(raw)
            ?.filter { it.id.isNotBlank() && it.name.isNotBlank() && it.url.isNotBlank() }
            ?.take(MAX_SERVERS)
            .orEmpty()

    /** Finds a server by exact id first, then by exact name (case-insensitive fallback). */
    fun find(servers: List<McpServer>, idOrName: String): McpServer? {
        val key = idOrName.trim()
        if (key.isEmpty()) return null
        servers.firstOrNull { it.id == key }?.let { return it }
        return servers.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }
}
