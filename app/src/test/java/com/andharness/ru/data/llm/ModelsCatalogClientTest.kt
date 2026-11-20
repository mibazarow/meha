package com.andharness.ru.data.llm

import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.llm.LlmAuthStyle
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ModelsCatalogClient]'s HTTP layer: honest auth errors on `GET /models`, the
 * Authorization header rule (only added for a non-blank key, so a keyless self-hosted server gets
 * none) and the credential-aware in-memory cache.
 *
 * Each payload carries a per-model `context_length`, so the llama.cpp `/props` fallback is never
 * attempted and the recorded request count stays deterministic.
 *
 * References:
 * - OpenAI list models: https://platform.openai.com/docs/api-reference/models/list
 * - MockWebServer (`takeRequest`, `requestCount`): https://square.github.io/okhttp/3.x/mockwebserver/
 * - MockWebServer 4 API: https://square.github.io/okhttp/4/okhttp/okhttp3/mockwebserver/MockWebServer/
 * - RecordedRequest (`getHeader`): https://square.github.io/okhttp/4/okhttp/okhttp3/mockwebserver/RecordedRequest/
 * - OkHttp `Request.Builder.header`: https://square.github.io/okhttp/4/okhttp/okhttp3/Request.Builder/
 * - OkHttp `Call.execute`: https://square.github.io/okhttp/4/okhttp/okhttp3/Call/
 * - HTTP 401 (auth required): https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/401
 */
class ModelsCatalogClientTest {

    private val modelsBody = """
        {"object":"list","data":[
            {"id":"test-model-a","context_length":4096},
            {"id":"test-model-b","context_length":8192}
        ]}
    """.trimIndent()

    /**
     * Minimal [LlmSettingsStore] double: the catalog client receives the key as a parameter, so only
     * the auth scheme matters here. Mirrors the doubles in `OpenAiLlmClientTest` /
     * `ContextManagerImplTest`.
     */
    private class FakeLlmSettingsStore(
        private val authConfig: LlmAuthConfig = LlmAuthConfig.DEFAULT,
    ) : LlmSettingsStore {
        override val config: Flow<LlmSettings> = flowOf(LlmSettings(endpoint = "", model = ""))
        override suspend fun updateEndpoint(value: String) = TODO("unused")
        override suspend fun updateModel(value: String) = TODO("unused")
        override suspend fun updateReasoningEffort(value: String) = TODO("unused")
        override suspend fun updateContextCompressionTokens(value: Long) = TODO("unused")
        override suspend fun getApiKey(): String? = null
        override suspend fun setApiKey(key: String) = TODO("unused")
        override suspend fun clearApiKey() = TODO("unused")
        override suspend fun getAuthConfig(): LlmAuthConfig = authConfig
        override suspend fun setAuthConfig(config: LlmAuthConfig) = TODO("unused")
        override suspend fun reset() = TODO("unused")
        override fun isConfigured(): Flow<Boolean> = flowOf(true)
    }

    private fun client(authConfig: LlmAuthConfig = LlmAuthConfig.DEFAULT) =
        ModelsCatalogClient(OkHttpClient(), FakeLlmSettingsStore(authConfig))

    /** (a) No key: the request must carry NO Authorization header, and the list still parses. */
    @Test
    fun sendsNoAuthorizationHeaderWhenKeyIsAbsent() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(modelsBody).addHeader("Content-Type", "application/json"))

        try {
            val catalog = client().fetch(server.url("/v1").toString())

            assertNull(
                "a keyless endpoint must not receive an Authorization header",
                server.takeRequest().getHeader("Authorization")
            )
            assertNull("a valid body must not produce an error", catalog.error)
            assertEquals(listOf("test-model-a", "test-model-b"), catalog.models)
        } finally {
            server.shutdown()
        }
    }

    /** (b) Key present: the request must carry `Authorization: Bearer <key>`. */
    @Test
    fun sendsBearerAuthorizationHeaderWhenKeyIsPresent() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(modelsBody).addHeader("Content-Type", "application/json"))

        try {
            val catalog = client().fetch(server.url("/v1").toString(), apiKey = "testkey")

            assertEquals("Bearer testkey", server.takeRequest().getHeader("Authorization"))
            assertEquals(listOf("test-model-a", "test-model-b"), catalog.models)
        } finally {
            server.shutdown()
        }
    }

    /**
     * (b2) A configured custom-header scheme (Anthropic-style) must be honoured: the key travels in
     * the named header with no `Bearer` prefix, and NO Authorization header is sent.
     */
    @Test
    fun sendsConfiguredCustomHeaderAndNoAuthorization() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(modelsBody).addHeader("Content-Type", "application/json"))

        try {
            val authConfig = LlmAuthConfig(style = LlmAuthStyle.HEADER, headerName = "x-api-key")
            val catalog = client(authConfig).fetch(server.url("/v1").toString(), apiKey = "testkey")

            val recorded = server.takeRequest()
            assertEquals("testkey", recorded.getHeader("x-api-key"))
            assertNull(
                "a custom-header scheme must not also send Authorization",
                recorded.getHeader("Authorization")
            )
            assertEquals(listOf("test-model-a", "test-model-b"), catalog.models)
        } finally {
            server.shutdown()
        }
    }

    /** (c) HTTP 401 must become an honest "check the API key" error, not a generic code message. */
    @Test
    fun http401IsReportedAsAnApiKeyProblem() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Authentication Fails"}}""")
                .addHeader("Content-Type", "application/json")
        )

        try {
            val catalog = client().fetch(server.url("/v1").toString(), apiKey = "badkey")

            val error = catalog.error
            assertTrue("401 must be surfaced as an error, was: $error", error != null && error.contains("401"))
            assertTrue("401 must tell the user to check the key, was: $error", error!!.contains("ключ"))
            assertTrue("a failed fetch must not yield models", catalog.models.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    /**
     * (d) The cache identity must include the key, not just the endpoint: fetching the same URL
     * with key A then key B must reach the server twice (no stale catalog), and a repeat with key B
     * must be served from the cache (no third request).
     */
    @Test
    fun cacheIsScopedToTheApiKeyFingerprint() = runBlocking {
        val server = MockWebServer()
        server.start()
        val endpoint = server.url("/v1").toString()
        server.enqueue(MockResponse().setBody(modelsBody).addHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(modelsBody).addHeader("Content-Type", "application/json"))

        try {
            val client = client()
            client.fetch(endpoint, apiKey = "key-a")
            client.fetch(endpoint, apiKey = "key-b")

            assertEquals(
                "a different key must not reuse the cached catalog",
                2, server.requestCount
            )
            assertEquals("Bearer key-a", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer key-b", server.takeRequest().getHeader("Authorization"))

            client.fetch(endpoint, apiKey = "key-b")
            assertEquals("the same key must be served from the cache", 2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
