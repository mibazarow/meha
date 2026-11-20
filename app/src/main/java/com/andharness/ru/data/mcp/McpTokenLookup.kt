package com.andharness.ru.data.mcp

import com.andharness.ru.domain.mcp.McpServer

/**
 * Supplies the optional auth-header VALUE for a server, without exposing the encrypted store to the
 * Android-free parts of the dynamic-exposure path.
 *
 * Production binds this to [McpSecretStore] (encrypted, Android Keystore); the JVM tests pass a
 * trivial stub, so the provider/cache/schema logic is testable without Android.
 */
fun interface McpTokenLookup {
    /** The stored token for [server], or `null` when the server has no auth header / no token. */
    fun tokenFor(server: McpServer): String?
}
