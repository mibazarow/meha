package com.andharness.ru.domain.audit

import com.andharness.ru.domain.common.Hashing

/**
 * Pure, Android-free hash-chain logic for the tool-call audit log (update.md idea #6).
 *
 * Each entry stores `hash = SHA-256(canonical(entry))`, where `canonical` includes the previous
 * entry's hash. [verify] recomputes the whole chain and reports the FIRST broken index, so a
 * tampered/removed/reordered row is detectable. Being pure `java.security`/`String` code, this is
 * unit-testable on the JVM without Android or Room.
 *
 * Bounded retention (`ToolAuditRetention`) legitimately drops the oldest rows, so [verify] accepts
 * an `anchorPrevHash` for the oldest retained entry; verification then covers every retained link
 * (it defaults to genesis, preserving full-log behavior).
 *
 * Official reference: `java.security.MessageDigest` —
 * https://developer.android.com/reference/java/security/MessageDigest
 */
object ToolAuditChain {

    /** First entry in the chain has no predecessor. */
    const val GENESIS_PREV_HASH = ""

    /** Unit separator: cannot appear in JSON tool arguments or in hex hashes. */
    private const val FIELD_SEPARATOR = "\u001F"

    /**
     * Deterministic canonical serialization of the hashable fields. Field order is fixed and values
     * are separated by [FIELD_SEPARATOR]; null fields become empty strings. `id` and `hash` are NOT
     * hashed (id is DB-assigned after hashing; hash is the output).
     */
    fun canonical(entry: ToolAuditEntry): String = listOf(
        entry.at.toString(),
        entry.chatId?.toString() ?: "",
        entry.tool,
        entry.argsPreview,
        if (entry.success) "1" else "0",
        entry.resultHash,
        entry.durationMs.toString(),
        entry.model ?: "",
        entry.prevHash
    ).joinToString(FIELD_SEPARATOR)

    /** SHA-256 of [canonical] (which already binds the row to [ToolAuditEntry.prevHash]). */
    fun computeHash(entry: ToolAuditEntry): String = sha256Hex(canonical(entry))

    /** Lowercase hex SHA-256 of the UTF-8 bytes of [text]. */
    fun sha256Hex(text: String): String = Hashing.sha256Hex(text)

    /** Result of [verify]: [ok] plus the first bad index/reason when the chain is broken. */
    data class VerifyResult(
        val ok: Boolean,
        val checked: Int,
        val firstBrokenIndex: Int? = null,
        val reason: String? = null
    )

    /**
     * Recomputes the chain over [entries] in storage order (oldest first) and reports the first
     * entry whose `prevHash` does not match the previous entry's `hash`, or whose `hash` does not
     * equal the recomputed value.
     *
     * [anchorPrevHash] is the `prevHash` the oldest entry is expected to carry. It defaults to
     * [GENESIS_PREV_HASH] (a full log). Because bounded retention prunes the oldest rows, the
     * repository passes the oldest retained entry's own `prevHash` so a truncated log still
     * verifies its retained links instead of reporting a false break at the head.
     */
    fun verify(
        entries: List<ToolAuditEntry>,
        anchorPrevHash: String = GENESIS_PREV_HASH
    ): VerifyResult {
        var expectedPrev = anchorPrevHash
        entries.forEachIndexed { index, entry ->
            if (entry.prevHash != expectedPrev) {
                return VerifyResult(
                    ok = false,
                    checked = index,
                    firstBrokenIndex = index,
                    reason = "связь с предыдущей записью нарушена"
                )
            }
            if (entry.hash != computeHash(entry)) {
                return VerifyResult(
                    ok = false,
                    checked = index,
                    firstBrokenIndex = index,
                    reason = "хеш записи не совпадает с содержимым"
                )
            }
            expectedPrev = entry.hash
        }
        return VerifyResult(ok = true, checked = entries.size)
    }
}
