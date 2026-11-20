package com.andharness.ru.domain.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM checks of the tamper-evident audit hash chain (update.md idea #6): a freshly built chain
 * verifies, a mutated row is reported at its index, and canonicalization/hashing is deterministic.
 */
class ToolAuditChainTest {

    private fun entry(
        at: Long,
        tool: String,
        prevHash: String,
        success: Boolean = true,
        argsPreview: String = "{}"
    ): ToolAuditEntry {
        val draft = ToolAuditEntry(
            id = 0,
            at = at,
            chatId = 1L,
            tool = tool,
            argsPreview = argsPreview,
            success = success,
            resultHash = ToolAuditChain.sha256Hex("result-$tool"),
            durationMs = 5,
            model = "model-x",
            prevHash = prevHash,
            hash = ""
        )
        return draft.copy(hash = ToolAuditChain.computeHash(draft))
    }

    private fun chain(): List<ToolAuditEntry> {
        val first = entry(at = 1000, tool = "files_list", prevHash = ToolAuditChain.GENESIS_PREV_HASH)
        val second = entry(at = 2000, tool = "files_read", prevHash = first.hash)
        val third = entry(at = 3000, tool = "files_write", prevHash = second.hash, success = false)
        return listOf(
            first.copy(id = 1),
            second.copy(id = 2),
            third.copy(id = 3)
        )
    }

    @Test
    fun freshChainVerifies() {
        val result = ToolAuditChain.verify(chain())
        assertTrue(result.ok)
        assertEquals(3, result.checked)
        assertEquals(null, result.firstBrokenIndex)
    }

    @Test
    fun emptyChainVerifiesTrivially() {
        val result = ToolAuditChain.verify(emptyList())
        assertTrue(result.ok)
        assertEquals(0, result.checked)
    }

    @Test
    fun mutatingARowReportsItsIndexAndHashMismatch() {
        val broken = chain().toMutableList()
        broken[1] = broken[1].copy(tool = "files_delete")

        val result = ToolAuditChain.verify(broken)
        assertFalse(result.ok)
        assertEquals(1, result.firstBrokenIndex)
        assertEquals("хеш записи не совпадает с содержимым", result.reason)
    }

    @Test
    fun aRemovedLinkBreaksTheChainAtTheNextRow() {
        val entries = chain()
        val withGap = listOf(entries[0], entries[2])

        val result = ToolAuditChain.verify(withGap)
        assertFalse(result.ok)
        assertEquals(1, result.firstBrokenIndex)
        assertEquals("связь с предыдущей записью нарушена", result.reason)
    }

    @Test
    fun canonicalizationIsDeterministic() {
        val entry = entry(at = 42, tool = "dns_lookup", prevHash = "abc")
        assertEquals(ToolAuditChain.canonical(entry), ToolAuditChain.canonical(entry))
        assertEquals(ToolAuditChain.computeHash(entry), ToolAuditChain.computeHash(entry))
        assertEquals(64, ToolAuditChain.computeHash(entry).length)
    }

    @Test
    fun differentContentProducesADifferentHash() {
        val base = entry(at = 42, tool = "dns_lookup", prevHash = "abc")
        assertNotEquals(
            ToolAuditChain.computeHash(base),
            ToolAuditChain.computeHash(base.copy(success = false))
        )
        assertNotEquals(
            ToolAuditChain.computeHash(base),
            ToolAuditChain.computeHash(base.copy(prevHash = "different"))
        )
    }

    @Test
    fun dbAssignedIdIsNotPartOfTheHash() {
        // id/hash are outputs, not inputs: changing them must not change the recomputed hash, which
        // is what lets the repository hash a row before Room assigns its autoincrement id.
        val entry = entry(at = 42, tool = "dns_lookup", prevHash = "abc")
        assertEquals(
            ToolAuditChain.computeHash(entry),
            ToolAuditChain.computeHash(entry.copy(id = 999, hash = "ignored"))
        )
    }

    @Test
    fun resultHashingIsStableAndSensitive() {
        assertEquals(ToolAuditChain.sha256Hex("abc"), ToolAuditChain.sha256Hex("abc"))
        assertNotEquals(ToolAuditChain.sha256Hex("abc"), ToolAuditChain.sha256Hex("abd"))
    }
}
