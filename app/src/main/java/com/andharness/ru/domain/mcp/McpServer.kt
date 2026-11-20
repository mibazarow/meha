package com.andharness.ru.domain.mcp

import kotlinx.serialization.Serializable

/**
 * One user-configured MCP (Model Context Protocol) server, managed entirely from chat.
 *
 * **Secrets are deliberately NOT part of this model.** The optional custom authentication header is
 * split in two: the header NAME lives here (it is not sensitive), while the header VALUE (a bearer
 * token / API key) is stored separately in the app's encrypted preferences
 * ([com.andharness.ru.data.mcp.McpSecretStore], an `EncryptedSharedPreferences` file whose master
 * key is in the Android Keystore) and is keyed by [id]. This guarantees the JSON file written by
 * [com.andharness.ru.data.mcp.McpServerStore] can never contain a plaintext token, even if the file
 * is read or backed up.
 *
 * `AUTO` transport is resolved at connect time (see [McpTransport]).
 *
 * Reference: https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
 */
@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val transport: McpTransport = McpTransport.AUTO,
    val enabled: Boolean = true,
    /** Header name for a custom auth token (e.g. `Authorization`); its value is in the secret store. */
    val authHeaderName: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
