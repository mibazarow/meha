package com.andharness.ru.data.llm

import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.llm.LlmAuthStyle
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-level tests for [LlmAuth]: each style must produce exactly the expected header/query shape,
 * and a blank key must never add any auth (so a self-hosted server keeps working).
 *
 * References:
 * - OkHttp `HttpUrl` / `addQueryParameter`: https://square.github.io/okhttp/4/okhttp/okhttp3/HttpUrl/
 * - OkHttp `Request.Builder.header`: https://square.github.io/okhttp/4/okhttp/okhttp3/Request.Builder/
 * - MockWebServer `RecordedRequest`: https://square.github.io/okhttp/4/okhttp/okhttp3/mockwebserver/RecordedRequest/
 */
class LlmAuthTest {

    private val client = OkHttpClient()

    /** Builds a request through [LlmAuth], executes it against [server] and returns what it saw. */
    private fun execute(server: MockWebServer, key: String?, config: LlmAuthConfig): RecordedRequest {
        val url = LlmAuth.urlWithAuth(server.url("/v1/chat/completions").toString(), key, config)
        val request = LlmAuth.applyHeader(Request.Builder().url(url), key, config).build()
        client.newCall(request).execute().close()
        return server.takeRequest()
    }

    private fun server(): MockWebServer = MockWebServer().apply {
        // Always answer 200 so tests that issue several requests never wait for a queued response.
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("{}")
        }
        start()
    }

    /** (a) BEARER with a key -> `Authorization: Bearer testkey`. */
    @Test
    fun bearerStyleSendsAuthorizationBearerHeader() {
        val server = server()
        try {
            val recorded = execute(server, "testkey", LlmAuthConfig(style = LlmAuthStyle.BEARER))

            assertEquals("Bearer testkey", recorded.getHeader("Authorization"))
            assertEquals(0, recorded.requestUrl!!.querySize)
        } finally {
            server.shutdown()
        }
    }

    /** (b) HEADER "x-api-key" -> the raw key in that header and no `Authorization`. */
    @Test
    fun headerStyleSendsTheKeyInTheCustomHeaderOnly() {
        val server = server()
        try {
            val recorded = execute(
                server,
                "testkey",
                LlmAuthConfig(style = LlmAuthStyle.HEADER, headerName = "x-api-key")
            )

            assertEquals("testkey", recorded.getHeader("x-api-key"))
            assertNull("a custom header must not also send a Bearer token", recorded.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    /** (c) QUERY "api_key" -> key in the URL, no auth header. */
    @Test
    fun queryStyleAppendsTheKeyToTheUrlAndSendsNoHeader() {
        val server = server()
        try {
            val recorded = execute(
                server,
                "testkey",
                LlmAuthConfig(style = LlmAuthStyle.QUERY, queryParam = "api_key")
            )

            assertEquals("testkey", recorded.requestUrl!!.queryParameter("api_key"))
            assertTrue(
                "the key must appear in the request path, was: ${recorded.path}",
                recorded.path!!.contains("api_key=testkey")
            )
            assertNull("a query-param key must not also send a header", recorded.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    /** (d) NONE -> neither a header nor a query parameter. */
    @Test
    fun noneStyleSendsNoAuthAtAll() {
        val server = server()
        try {
            val recorded = execute(server, "testkey", LlmAuthConfig(style = LlmAuthStyle.NONE))

            assertNull(recorded.getHeader("Authorization"))
            assertEquals("NONE must not add a query parameter either", 0, recorded.requestUrl!!.querySize)
        } finally {
            server.shutdown()
        }
    }

    /** (e) A blank key must never add auth, whatever the style asks for (keyless self-hosted). */
    @Test
    fun blankKeyNeverAddsAuthForAnyStyle() {
        val configs = listOf(
            LlmAuthConfig(style = LlmAuthStyle.BEARER),
            LlmAuthConfig(style = LlmAuthStyle.HEADER, headerName = "x-api-key"),
            LlmAuthConfig(style = LlmAuthStyle.QUERY, queryParam = "api_key"),
            LlmAuthConfig(style = LlmAuthStyle.NONE)
        )
        val server = server()
        try {
            configs.forEach { config ->
                val recorded = execute(server, " ", config)

                assertNull(
                    "a blank key must not send a header for ${config.style}",
                    recorded.getHeader("Authorization")
                )
                assertNull(
                    "a blank key must not send a custom header for ${config.style}",
                    recorded.getHeader("x-api-key")
                )
                assertEquals(
                    "a blank key must not add a query parameter for ${config.style}",
                    0, recorded.requestUrl!!.querySize
                )
            }
        } finally {
            server.shutdown()
        }
    }

    /** (f) HEADER/QUERY with a blank name fall back to Bearer. */
    @Test
    fun blankHeaderNameAndBlankQueryParamFallBackToBearer() {
        val server = server()
        try {
            val headerFallback = execute(
                server, "testkey",
                LlmAuthConfig(style = LlmAuthStyle.HEADER, headerName = "  ")
            )
            assertEquals("Bearer testkey", headerFallback.getHeader("Authorization"))
            assertEquals(0, headerFallback.requestUrl!!.querySize)

            val queryFallback = execute(
                server, "testkey",
                LlmAuthConfig(style = LlmAuthStyle.QUERY, queryParam = "")
            )
            assertEquals("Bearer testkey", queryFallback.getHeader("Authorization"))
            assertEquals(0, queryFallback.requestUrl!!.querySize)
        } finally {
            server.shutdown()
        }
    }
}
