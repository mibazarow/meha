package com.andharness.ru.data.mcp

import com.andharness.ru.domain.mcp.McpServer
import com.andharness.ru.domain.mcp.McpServerCodec
import com.andharness.ru.domain.mcp.McpServerRules
import com.andharness.ru.domain.mcp.McpTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Persistent registry of user-configured MCP servers, backed by a single JSON file
 * `<rootDir>/mcp_servers.json` written atomically (temp file + rename) — the same pattern as
 * `SkillStore`/`GlobalProfileStore`.
 *
 * It is deliberately **Android-free** (it only takes a directory [File]), so CRUD, bounds, name
 * lookup and atomic persistence are exercised on the JVM (`McpServerStoreTest`); the DI module
 * supplies `mcp` under `context.filesDir`.
 *
 * **Secrets never live here.** [McpServer] has no token field; the optional auth header VALUE is
 * kept in the encrypted [McpSecretStore] keyed by the server id. The JSON written by this store
 * therefore cannot contain a plaintext token (`McpServerStoreTest` asserts this on disk).
 *
 * All access is `@Synchronized`; a corrupt/foreign file degrades to an empty list instead of
 * throwing, and a failed disk write leaves the in-memory state unchanged (a failed save stays
 * visible rather than silently reported as success). The registry is bounded to
 * [McpServerCodec.MAX_SERVERS] — adding past it is rejected so user config is never dropped.
 */
class McpServerStore(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val _servers = MutableStateFlow(load())

    /** Current registry as an observable list (storage order, oldest written first). */
    val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    /** All configured servers, oldest first. Never throws. */
    @Synchronized
    fun list(): List<McpServer> = _servers.value

    /** Number of configured servers. */
    @Synchronized
    fun count(): Int = _servers.value.size

    /** Finds a server by exact id, then by name (case-insensitive). */
    @Synchronized
    fun get(idOrName: String): McpServer? = McpServerCodec.find(_servers.value, idOrName)

    /**
     * Adds or updates a server.
     *
     * - `id == null` → update the server with the same name, or append a new one when the name is
     *   free (re-adding an existing name is how the agent re-enables/edits a server from chat);
     * - `id != null` → update that exact server, or [McpWriteOutcome.NOT_FOUND].
     *
     * An update always sets `enabled = true`. A blank/over-long name or an invalid URL is rejected
     * as [McpWriteOutcome.INVALID]; adding a new server beyond [McpServerCodec.MAX_SERVERS] is
     * rejected as [McpWriteOutcome.LIMIT] (nothing is ever silently dropped).
     */
    @Synchronized
    fun put(
        id: String?,
        name: String,
        url: String,
        transport: McpTransport = McpTransport.AUTO,
        authHeaderName: String? = null,
        now: Long = System.currentTimeMillis(),
    ): McpWriteResult {
        val cleanName = McpServerRules.normalizeName(name)
            ?: return McpWriteResult(null, McpWriteOutcome.INVALID)
        val cleanUrl = McpServerRules.normalizeUrl(url)
            ?: return McpWriteResult(null, McpWriteOutcome.INVALID)
        val cleanHeader = authHeaderName?.let { McpServerRules.normalizeHeaderName(it) }
        if (!authHeaderName.isNullOrBlank() && cleanHeader == null) {
            return McpWriteResult(null, McpWriteOutcome.INVALID)
        }

        val current = _servers.value
        val existing = if (id.isNullOrBlank()) {
            current.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
        } else {
            current.firstOrNull { it.id == id }
                ?: return McpWriteResult(null, McpWriteOutcome.NOT_FOUND)
        }

        if (existing != null) {
            val updated = existing.copy(
                name = cleanName,
                url = cleanUrl,
                transport = transport,
                enabled = true,
                authHeaderName = cleanHeader,
                updatedAt = now,
            )
            persist(current.map { if (it.id == existing.id) updated else it })
            return McpWriteResult(updated, McpWriteOutcome.UPDATED)
        }

        if (current.size >= McpServerCodec.MAX_SERVERS) {
            return McpWriteResult(null, McpWriteOutcome.LIMIT)
        }
        val server = McpServer(
            id = nextId(current),
            name = cleanName,
            url = cleanUrl,
            transport = transport,
            enabled = true,
            authHeaderName = cleanHeader,
            createdAt = now,
            updatedAt = now,
        )
        persist(current + server)
        return McpWriteResult(server, McpWriteOutcome.CREATED)
    }

    /** Enables/disables a server by id or name; returns true only when something matched. */
    @Synchronized
    fun setEnabled(idOrName: String, enabled: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val server = McpServerCodec.find(_servers.value, idOrName) ?: return false
        persist(_servers.value.map { if (it.id == server.id) it.copy(enabled = enabled, updatedAt = now) else it })
        return true
    }

    /** Removes a server by id or name; returns the removed server, or null when nothing matched. */
    @Synchronized
    fun delete(idOrName: String): McpServer? {
        val server = McpServerCodec.find(_servers.value, idOrName) ?: return null
        persist(_servers.value.filterNot { it.id == server.id })
        return server
    }

    /** The bounded prompt-context block for the current registry. Never throws. */
    @Synchronized
    fun renderBlock(limit: Int = McpServerRules.MAX_BLOCK_CHARS): String =
        McpServerRules.renderBlock(_servers.value, limit)

    private fun load(): List<McpServer> = try {
        if (file.isFile) McpServerCodec.decodeServers(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Writes [next] atomically and only then publishes it; a failed write keeps the old state. */
    private fun persist(next: List<McpServer>) {
        val bounded = next.take(McpServerCodec.MAX_SERVERS)
        try {
            rootDir.mkdirs()
            val temp = File(rootDir, "$FILE_NAME.tmp")
            val encoded = McpServerCodec.encodeServers(bounded)
            temp.writeText(encoded)
            if (!temp.renameTo(file)) {
                // Rename can fail on some filesystems; fall back to a direct write so the save still
                // lands, and clean up the temp file.
                file.writeText(encoded)
                temp.delete()
            }
            _servers.value = bounded
        } catch (_: Exception) {
            // Best-effort: keep the in-memory state unchanged so a failed disk write is visible
            // (the list simply does not change) rather than silently reporting success.
        }
    }

    private fun nextId(existing: List<McpServer>): String {
        val taken = existing.mapTo(HashSet()) { it.id }
        while (true) {
            val candidate = "mcp" + UUID.randomUUID().toString().take(8)
            if (candidate !in taken) return candidate
        }
    }

    companion object {
        /** Subdirectory of `filesDir` that holds the MCP registry. */
        const val DIR_NAME = "mcp"

        /** Single JSON file inside [DIR_NAME]. */
        const val FILE_NAME = "mcp_servers.json"
    }
}
