package com.andharness.ru.data.tools.web

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * JVM integration tests for BUG-047 wiring in `web_fetch`: the request carries a realistic mobile
 * Chrome UA (no `; wv`) and a locale-derived `Accept-Language`, and anti-bot responses (403/429 or a
 * WAF stub body) are reported honestly instead of being scraped.
 *
 * References:
 * - MockWebServer: https://square.github.io/okhttp/4/okhttp/okhttp3/mockwebserver/MockWebServer/
 */
class WebFetchBotStubTest {

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

    private fun handler(acceptLanguage: () -> String = { WebBrowserConfig.buildAcceptLanguage(Locale("ru", "RU")) }) =
        WebFetchToolsHandler(OkHttpClient(), 5, acceptLanguage)

    private fun fetch(handler: WebFetchToolsHandler, url: String) = runBlocking {
        handler.all().first().execute(buildJsonObject { put("url", url) })
    }

    @Test
    fun fetchSendsMobileChromeUaWithoutWebViewMarker() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><head><title>Ok</title></head><body><p>hi</p></body></html>"))

        val result = fetch(handler(), server.url("/").toString())

        assertTrue(result.output, result.success)
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        val ua = request!!.getHeader("User-Agent")
        assertNotNull(ua)
        assertTrue("UA must contain Chrome: $ua", ua!!.contains("Chrome"))
        assertFalse("UA must not carry the WebView `; wv` marker: $ua", ua.contains("; wv"))
    }

    @Test
    fun fetchSendsLocaleDerivedAcceptLanguage() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><head><title>Ok</title></head><body><p>hi</p></body></html>"))

        val result = fetch(handler(), server.url("/").toString())

        assertTrue(result.output, result.success)
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7", request!!.getHeader("Accept-Language"))
    }

    @Test
    fun http403IsReportedAsBlockedNotScraped() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<html><head><title>403</title></head><body>forbidden</body></html>"))

        val result = fetch(handler(), server.url("/").toString())

        assertFalse(result.output, result.success)
        assertTrue(result.output, result.output.contains("403") || result.output.contains("заблокир"))
    }

    @Test
    fun http429IsReportedAsRateLimited() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        val result = fetch(handler(), server.url("/").toString())

        assertFalse(result.output, result.success)
        assertTrue(result.output, result.output.contains("429") || result.output.contains("заблокир") || result.output.contains("request"))
    }

    @Test
    fun cloudflareStubBodyIsReportedHonestly() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("<html><head><title>Just a moment…</title></head><body><p>Checking your browser before accessing the website.</p></body></html>"),
        )

        val result = fetch(handler(), server.url("/").toString())

        assertFalse(result.output, result.success)
        assertTrue(result.output, result.output.contains("антибот") || result.output.contains("Checking your browser") || result.output.contains("Just a moment"))
    }
}
