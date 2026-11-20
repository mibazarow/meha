package com.andharness.ru.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of the [LinkIntent] scheme allow-list: web/mail/phone destinations are openable,
 * script/data/file-style destinations are refused, and malformed or scheme-less strings never
 * produce an intent.
 */
class LinkIntentTest {

    @Test
    fun httpAndHttpsAreOpenable() {
        assertTrue(LinkIntent.isSafe("https://example.com/path?q=1#frag"))
        assertTrue(LinkIntent.isSafe("http://example.com"))
        assertTrue(LinkIntent.isSafe("https://user:pass@host.example:8443/a/b"))
    }

    @Test
    fun schemeIsCaseInsensitiveAndLowerCased() {
        assertEquals("https", LinkIntent.scheme("HTTPS://Example.COM"))
        assertTrue(LinkIntent.isSafe("HTTPS://example.com"))
    }

    @Test
    fun mailtoAndTelAreOpenable() {
        assertTrue(LinkIntent.isSafe("mailto:someone@example.com"))
        assertTrue(LinkIntent.isSafe("tel:+70000000000"))
    }

    @Test
    fun dangerousSchemesAreRejected() {
        listOf(
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "vbscript:msgbox",
            "content://contacts/people/1",
            "intent://example.com#Intent;scheme=https;end"
        ).forEach { destination ->
            assertFalse("must reject $destination", LinkIntent.isSafe(destination))
            assertNull("no intent for $destination", LinkIntent.buildViewIntent(destination))
        }
    }

    @Test
    fun malformedOrSchemelessStringsAreRejected() {
        listOf(
            "",
            "   ",
            "example.com", // no scheme at all
            ":nope", // colon but no scheme
            "1https://example.com", // digit-leading pseudo-scheme
            "http://", // scheme + separator but no authority
            "https:/example.com", // single slash — no authority
            "http://exa mple.com", // embedded space
            "https://example.com\njavascript:alert(1)", // newline injection
            "https://exam\tple.com" // embedded tab
        ).forEach { destination ->
            assertFalse("must reject [$destination]", LinkIntent.isSafe(destination))
        }
    }

    @Test
    fun sanitizeTrimsSurroundingWhitespace() {
        assertEquals("https://example.com/a", LinkIntent.sanitize("  https://example.com/a \n"))
        assertNull(LinkIntent.sanitize("  javascript:alert(1)  "))
    }

    @Test
    fun buildViewIntentIsProducedOnlyForSafeLinks() {
        assertNotNull(LinkIntent.buildViewIntent("https://example.com"))
        assertNotNull(LinkIntent.buildViewIntent("mailto:someone@example.com"))
        assertNull(LinkIntent.buildViewIntent("javascript:alert(1)"))
        assertNull(LinkIntent.buildViewIntent("http://"))
    }
}
