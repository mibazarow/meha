package com.andharness.ru.domain.audit

/**
 * One append-only entry in the tamper-evident tool-call audit log (update.md «Идеи следующих волн»,
 * idea #6). Every tool execution is recorded once; the rows form a SHA-256 hash chain via
 * [prevHash]/[hash], so any edit/removal/reorder is detectable by [ToolAuditChain.verify].
 *
 * Privacy: [argsPreview] is ALWAYS a redacted, truncated copy of the arguments — raw secrets are
 * never stored (see [ToolAuditRedactor]). The full result is not stored either; only its SHA-256
 * ([resultHash]) is kept, enough to prove what was returned without keeping the payload.
 *
 * [id] is assigned by Room (autoGenerate) and is deliberately NOT part of the hash canonicalization
 * (it does not exist yet when the hash is computed). The chain still detects reordering because each
 * row is bound to the previous row's [hash].
 */
data class ToolAuditEntry(
    val id: Long,
    val at: Long,
    val chatId: Long?,
    val tool: String,
    val argsPreview: String,
    val success: Boolean,
    val resultHash: String,
    val durationMs: Long,
    val model: String?,
    val prevHash: String,
    val hash: String
)
