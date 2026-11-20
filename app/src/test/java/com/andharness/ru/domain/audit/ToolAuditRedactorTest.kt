package com.andharness.ru.domain.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM checks of the audit-log redaction (update.md idea #6): obvious secrets must never be stored
 * in an argument preview, while harmless arguments stay readable.
 */
class ToolAuditRedactorTest {

    @Test
    fun apiKeyValueIsMasked() {
        val redacted = ToolAuditRedactor.redact("""{"api_key":"sk-abcdef123456","path":"Documents/a.txt"}""")
        assertFalse(redacted.contains("sk-abcdef123456"))
        assertTrue(redacted.contains(ToolAuditRedactor.MASK))
        assertTrue("harmless arguments must survive", redacted.contains("Documents/a.txt"))
    }

    @Test
    fun passwordAndTokenValuesAreMasked() {
        val password = ToolAuditRedactor.redact("""{"password":"hunter2"}""")
        assertFalse(password.contains("hunter2"))
        val token = ToolAuditRedactor.redact("""{"access_token":"aaa.bbb.ccc"}""")
        assertFalse(token.contains("aaa.bbb.ccc"))
    }

    @Test
    fun bearerTokensAreMasked() {
        val redacted = ToolAuditRedactor.redact("Authorization: Bearer abcdefghijklmnop")
        assertFalse(redacted.contains("abcdefghijklmnop"))
        assertTrue(redacted.contains(ToolAuditRedactor.MASK))
    }

    @Test
    fun bareOpenAiStyleKeyIsMasked() {
        val redacted = ToolAuditRedactor.redact("""{"note":"use sk-1234567890abcdef please"}""")
        assertFalse(redacted.contains("sk-1234567890abcdef"))
    }

    @Test
    fun harmlessArgumentsAreUnchanged() {
        val raw = """{"path":"Documents/notes.txt","limit":"10"}"""
        assertEquals(raw, ToolAuditRedactor.redact(raw))
    }

    @Test
    fun previewTruncatesLongArguments() {
        val long = "x".repeat(ToolAuditRedactor.MAX_PREVIEW_LENGTH + 500)
        val preview = ToolAuditRedactor.preview(long)
        assertEquals(ToolAuditRedactor.MAX_PREVIEW_LENGTH + 1, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun previewRedactsBeforeTruncating() {
        val preview = ToolAuditRedactor.preview("""{"token":"supersecretvalue"}""")
        assertFalse(preview.contains("supersecretvalue"))
        assertTrue(preview.contains(ToolAuditRedactor.MASK))
    }
}
