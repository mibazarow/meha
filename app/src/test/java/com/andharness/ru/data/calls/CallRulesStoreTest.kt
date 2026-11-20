package com.andharness.ru.data.calls

import com.andharness.ru.domain.calls.AutoReplyCooldown
import com.andharness.ru.domain.calls.CallRules
import com.andharness.ru.domain.calls.CallRulesCodec
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the file-backed [CallRulesStore]. It is Android-free by construction (it only takes a
 * directory), so real file CRUD, persistence across instances, bounds, corrupt-file degradation and
 * the auto-reply cooldown log are exercised on a temp directory — no Android runtime involved.
 */
class CallRulesStoreTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("call-rules-test").toFile().also { dirs += it }

    private fun store(dir: File = newDir()) = CallRulesStore(dir)

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun defaultsAreAStrictNoOp() {
        val store = store()
        assertEquals(CallRules(), store.current())
        assertFalse(store.current().enabled)
        assertTrue(store.current().blockedNumbers.isEmpty())
        assertTrue(store.current().allowNumbers.isEmpty())
        assertFalse(store.current().autoReplySms)
        assertEquals(CallRules.DEFAULT_AUTO_REPLY_TEXT, store.current().autoReplyText)
    }

    @Test
    fun everySetterMutatesTheCorrespondingField() {
        val store = store()
        store.setEnabled(true)
        store.setBlockUnknown(true)
        store.setBlockedNumbers(listOf("+7 900 123-45-67", "+79001234567"))
        store.setAllowNumbers(listOf("+79007654321"))
        store.setAutoReplySms(true)
        store.setAutoReplyText("  на связи позже  ")
        store.setAutoReplyWhenBusy(true)
        store.setCallBackReminder(true)

        val rules = store.current()
        assertTrue(rules.enabled)
        assertTrue(rules.blockUnknown)
        assertEquals(listOf("+79001234567"), rules.blockedNumbers)
        assertEquals(listOf("+79007654321"), rules.allowNumbers)
        assertTrue(rules.autoReplySms)
        assertEquals("на связи позже", rules.autoReplyText)
        assertTrue(rules.autoReplyWhenBusy)
        assertTrue(rules.callBackReminder)
    }

    @Test
    fun statePersistsAcrossInstances() {
        val dir = newDir()
        val first = store(dir)
        first.setEnabled(true)
        first.setBlockUnknown(true)
        first.setBlockedNumbers(listOf("+79001234567"))
        first.setAutoReplySms(true)

        val second = store(dir)
        assertTrue(second.current().enabled)
        assertTrue(second.current().blockUnknown)
        assertEquals(listOf("+79001234567"), second.current().blockedNumbers)
        assertTrue(second.current().autoReplySms)
    }

    @Test
    fun corruptFileDegradesToDefaultsAndStillWrites() {
        val dir = newDir()
        File(dir, CallRulesStore.FILE_NAME).writeText("{ not json")
        val store = store(dir)
        assertEquals(CallRules(), store.current())

        store.setEnabled(true)
        assertTrue(store(dir).current().enabled)
    }

    @Test
    fun numberCapIsEnforced() {
        val store = store()
        store.setBlockedNumbers((0..CallRulesCodec.MAX_NUMBERS + 20).map { "+7900" + it.toString().padStart(6, '0') })
        assertEquals(CallRulesCodec.MAX_NUMBERS, store.current().blockedNumbers.size)
    }

    @Test
    fun autoReplyLogTracksCooldownAndClears() {
        val store = store()
        assertNull(store.lastAutoReplyAt("+79001234567"))
        assertFalse(store.isAutoReplyCoolingDown("+79001234567", nowMs = 1_000L))

        store.noteAutoReply("+7 900 123-45-67", atMs = 1_000L)
        assertEquals(1_000L, store.lastAutoReplyAt("+79001234567"))
        assertTrue(store.isAutoReplyCoolingDown("+79001234567", nowMs = 2_000L))
        assertFalse(
            store.isAutoReplyCoolingDown(
                "+79001234567",
                nowMs = 1_000L + AutoReplyCooldown.DEFAULT_COOLDOWN_MS,
            ),
        )

        assertTrue(store.clearAutoReplyLog())
        assertFalse(store.clearAutoReplyLog())
        assertNull(store.lastAutoReplyAt("+79001234567"))
    }

    @Test
    fun autoReplyLogIsCapped() {
        val store = store()
        repeat(CallRulesCodec.MAX_REPLY_LOG + 30) { i ->
            store.noteAutoReply("+7900" + i.toString().padStart(6, '0'), atMs = (i + 1).toLong())
        }
        assertNull(store.lastAutoReplyAt("+7900000000"))
        assertEquals(
            (CallRulesCodec.MAX_REPLY_LOG + 30).toLong(),
            store.lastAutoReplyAt("+7900" + (CallRulesCodec.MAX_REPLY_LOG + 29).toString().padStart(6, '0')),
        )
    }

    @Test
    fun stateFlowReflectsMutations() {
        val store = store()
        store.setEnabled(true)
        assertTrue(store.rules.value.enabled)
        assertTrue(store.state.value.rules.enabled)
    }
}
