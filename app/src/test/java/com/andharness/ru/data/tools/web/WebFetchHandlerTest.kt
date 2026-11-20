package com.andharness.ru.data.tools.web

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * JVM tests for `web_fetch` after BUG-007: the tool no longer uses a `WebView` (which had to run on
 * the main thread and caused the ANR) but a bounded OkHttp GET on `Dispatchers.IO`. Covered here:
 * the pure HTML→text helper, a successful fetch, an honest non-2xx failure and a bounded timeout.
 *
 * References:
 * - MockWebServer: https://square.github.io/okhttp/4/okhttp/okhttp3/mockwebserver/MockWebServer/
 * - `SocketPolicy.NO_RESPONSE`: https://square.github.io/okhttp/4/okhttp/okhttp3/mockwebserver/SocketPolicy/
 */
class WebFetchHandlerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun handler(timeoutSeconds: Long = 5) = WebFetchToolsHandler(OkHttpClient(), timeoutSeconds)

    private fun fetch(url: String, timeoutSeconds: Long = 5) = runBlocking {
        handler(timeoutSeconds).all().first().execute(buildJsonObject { put("url", url) })
    }

    @Test
    fun htmlToTextKeepsTitleAndVisibleTextButDropsScripts() {
        val html = """
            <html><head><title>Заголовок</title>
            <script>var secret="JS_SECRET";</script>
            <style>.x{color:red}</style></head>
            <body><h1>Привет</h1><p>Первый абзац.</p><p>Второй &amp; абзац.</p></body></html>
        """.trimIndent()

        val text = htmlToText(html)

        assertNotNull(text)
        assertTrue(text!!, text.contains("Заголовок"))
        assertTrue(text, text.contains("Привет"))
        assertTrue(text, text.contains("Первый абзац."))
        assertTrue(text, text.contains("Второй & абзац."))
        assertFalse("script content must be dropped", text.contains("JS_SECRET"))
        assertFalse("style content must be dropped", text.contains("color:red"))
    }

    @Test
    fun htmlToTextReturnsNullForBlankPage() {
        assertTrue(htmlToText("<html><body><script>1</script></body></html>") == null)
    }

    @Test
    fun successfulFetchReturnsReadableText() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("<html><head><title>Doc</title></head><body><p>Hello web</p></body></html>"),
        )

        val result = fetch(server.url("/page").toString())

        assertTrue(result.output, result.success)
        assertTrue(result.output, result.output.contains("Doc"))
        assertTrue(result.output, result.output.contains("Hello web"))
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertTrue(request!!.getHeader("User-Agent")!!.contains("Chrome"))
    }

    @Test
    fun non2xxReturnsHonestFailure() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))

        val result = fetch(server.url("/missing").toString())

        assertFalse(result.output, result.success)
        assertTrue(result.output, result.output.contains("HTTP 404"))
    }

    @Test
    fun timeoutReturnsBoundedFailureInsteadOfHanging() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val startedAt = System.currentTimeMillis()
        val result = fetch(server.url("/slow").toString(), timeoutSeconds = 1)
        val elapsed = System.currentTimeMillis() - startedAt

        assertFalse(result.output, result.success)
        assertTrue("must fail honestly, got: ${result.output}", result.output.contains("Ошибка загрузки"))
        assertTrue("must be bounded, took ${elapsed}ms", elapsed < 10_000)
    }
}
