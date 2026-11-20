package com.andharness.ru.di

import android.content.Context
import com.andharness.ru.data.mcp.McpClient
import com.andharness.ru.data.mcp.McpSecretStore
import com.andharness.ru.data.mcp.McpServerStore
import com.andharness.ru.data.mcp.McpTokenLookup
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * MCP (Model Context Protocol) bindings.
 *
 * The registry is the Android-free [McpServerStore] (one JSON file under `<filesDir>/mcp`); the
 * only thing this module supplies is the app's private files directory. The auth token store
 * ([com.andharness.ru.data.mcp.McpSecretStore]) needs `@ApplicationContext` itself and is provided
 * by its own `@Inject` constructor (EncryptedSharedPreferences + Android Keystore).
 *
 * [McpClient] is a stateless, single-shot HTTP client with bounded timeouts and its own OkHttp
 * instance — deliberately NOT the agent's long-read LLM client.
 */
@Module
@InstallIn(SingletonComponent::class)
object McpModule {

    /** Persistent MCP server registry: one JSON file under `<filesDir>/mcp`. */
    @Provides
    @Singleton
    fun provideMcpServerStore(@ApplicationContext context: Context): McpServerStore =
        McpServerStore(File(context.filesDir, McpServerStore.DIR_NAME))

    /** Bounded-timeout JSON-RPC client used by the management tools and by tool exposure. */
    @Provides
    @Singleton
    fun provideMcpClient(): McpClient = McpClient()

    /**
     * Token lookup for the dynamic-exposure path: the encrypted [McpSecretStore] behind the
     * Android-free [McpTokenLookup] seam. The provider only reads a token when the server declares
     * an auth header, and never logs or returns it.
     */
    @Provides
    @Singleton
    fun provideMcpTokenLookup(secrets: McpSecretStore): McpTokenLookup =
        McpTokenLookup { server ->
            server.authHeaderName?.let { secrets.authHeaderValue(server.id) }
        }
}
