package com.andharness.ru.data.tools.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * JVM (Android-free) tests for the browser hardening done for BUG-047:
 * - the mobile Chrome User-Agent has no WebView `; wv` marker;
 * - the `Accept-Language` header is built from the device locale;
 * - the honest bot-stub detector fires on WAF/captcha/rate-limit stubs and stays quiet on real pages.
 */
class WebBrowserConfigTest {

    @Test
    fun mobileChromeUaHasNoWebViewMarker() {
        val ua = WebBrowserConfig.MOBILE_CHROME_UA

        // A real mobile Chrome UA, never an in-app web view.
        assertTrue("UA must not carry the WebView `; wv` marker: $ua", ua.contains("; wv").not())
        assertTrue("UA should look like Chrome: $ua", ua.contains("Chrome"))
        assertTrue("UA should look like Mobile Safari: $ua", ua.contains("Safari"))
        // The fetch path and the WebView path must share one documented constant.
        assertEquals(WebContentExtractor.MOBILE_CHROME_UA, WebBrowserConfig.MOBILE_CHROME_UA)
    }

    @Test
    fun acceptLanguageBuiltFromRussianLocale() {
        val header = WebBrowserConfig.buildAcceptLanguage(Locale("ru", "RU"))

        assertEquals("ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7", header)
    }

    @Test
    fun acceptLanguageBuiltFromEnglishLocale() {
        val header = WebBrowserConfig.buildAcceptLanguage(Locale("en", "US"))

        assertEquals("en-US,en;q=0.9,en-US;q=0.8,en;q=0.7", header)
    }

    @Test
    fun acceptLanguageAlwaysFallsBackToEnglish() {
        val header = WebBrowserConfig.buildAcceptLanguage(Locale("de", "DE"))

        assertTrue("header must be non-blank: $header", header.isNotBlank())
        assertTrue(header.contains("de-DE,de;q=0.9"))
        assertTrue(header.endsWith("en;q=0.7"))
    }

    @Test
    fun botStubDetectedOnHttp403() {
        val stub = WebBrowserConfig.detectBotStub("", "", 403)

        assertNotNull(stub)
        assertEquals("http_status_403", stub!!.signal)
        assertTrue(stub.reason.isNotBlank())
    }

    @Test
    fun botStubDetectedOnHttp429() {
        val stub = WebBrowserConfig.detectBotStub("", "", 429)

        assertNotNull(stub)
        assertEquals("http_status_429", stub!!.signal)
    }

    @Test
    fun botStubDetectedOnCloudflareJustAMoment() {
        val stub = WebBrowserConfig.detectBotStub("Just a moment…", "Checking your browser before accessing the website.")

        assertNotNull(stub)
        assertEquals("cloudflare", stub!!.signal)
    }

    @Test
    fun botStubDetectedOnCloudflareAttentionRequired() {
        val stub = WebBrowserConfig.detectBotStub("Attention Required! Cloudflare", "You have blocked Cloudflare.")

        assertNotNull(stub)
        assertEquals("cloudflare_attention", stub!!.signal)
    }

    @Test
    fun botStubDetectedOnCaptcha() {
        val stub = WebBrowserConfig.detectBotStub("Verify you are human", "Please complete the captcha to continue.")

        assertNotNull(stub)
        assertEquals("captcha", stub!!.signal)
    }

    @Test
    fun botStubDetectedOnAccessDenied() {
        val stub = WebBrowserConfig.detectBotStub("Access denied", "Your request was blocked. Too Many Requests.")

        assertNotNull(stub)
        assertEquals("access_denied", stub!!.signal)
    }

    @Test
    fun botStubNotDetectedOnNormalPage() {
        val stub = WebBrowserConfig.detectBotStub(
            "Example Domain",
            "Example Domain is used for illustrative examples in documents. You may use this " +
                "domain in literature without prior coordination or asking for permission.",
        )

        assertNull(stub)
    }

    @Test
    fun botStubNotDetectedOnHttp200Or404WithRealContent() {
        assertNull(WebBrowserConfig.detectBotStub("Docs", "Real page content here", 200))
        // 404 is a genuine not-found, not an anti-bot stub.
        assertNull(WebBrowserConfig.detectBotStub("Not Found", "The requested resource was not found.", 404))
    }
}
