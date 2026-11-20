package com.andharness.ru.data.sms

import com.andharness.ru.domain.sms.SmsPhotoRule
import com.andharness.ru.domain.sms.SmsPhotoRuleCodec
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the file-backed [SmsPhotoRuleStore]. It is Android-free by construction (it only
 * takes a directory), so real file CRUD, persistence, bounds, corrupt-file degradation and the
 * cooldown log are exercised on a temp directory — no Android runtime involved.
 */
class SmsPhotoRuleStoreTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("sms-photo-rule-test").toFile().also { dirs += it }

    private fun store(dir: File = newDir()) = SmsPhotoRuleStore(dir)

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun defaultsAreAnOptInNoOp() {
        val store = store()
        assertFalse(store.current().enabled)
        assertEquals(SmsPhotoRule.DEFAULT_REPLY_PREFIX, store.current().replyTextPrefix)
        assertEquals(SmsPhotoRule.DEFAULT_COOLDOWN_SECONDS, store.current().cooldownSeconds)
    }

    @Test
    fun settersMutateAndPersistAcrossInstances() {
        val dir = newDir()
        val first = SmsPhotoRuleStore(dir)
        first.setEnabled(true)
        first.setReplyTextPrefix("  Снимок:  ")
        first.setCooldownSeconds(120)

        val second = SmsPhotoRuleStore(dir)
        assertTrue(second.current().enabled)
        assertEquals("Снимок:", second.current().replyTextPrefix)
        assertEquals(120, second.current().cooldownSeconds)
    }

    @Test
    fun corruptFileDegradesToDefaults() {
        val dir = newDir()
        File(dir, SmsPhotoRuleStore.FILE_NAME).writeText("{ not json ]")
        val store = SmsPhotoRuleStore(dir)
        assertEquals(SmsPhotoRule(), store.current())
    }

    @Test
    fun cooldownLogIsPerNormalizedNumber() {
        val store = store()
        store.setCooldownSeconds(60)
        store.noteReply("+7 900 123-45-67", 10_000L)

        assertTrue(store.isCoolingDown("+79001234567", 20_000L))
        assertFalse(store.isCoolingDown("+79001234567", 10_000L + 60_000L))
        assertFalse(store.isCoolingDown("+79007654321", 20_000L))
        assertEquals(10_000L, store.lastReplyAt("+79001234567"))
        assertNull(store.lastReplyAt("nope"))
    }

    @Test
    fun replyLogIsBoundedAndClearable() {
        val store = store()
        repeat(SmsPhotoRuleCodec.MAX_REPLY_LOG + 25) { index ->
            store.noteReply("+7900123${index.toString().padStart(4, '0')}", index.toLong() + 1)
        }
        assertEquals(SmsPhotoRuleCodec.MAX_REPLY_LOG, store.state.value.replyLog.size)
        assertTrue(store.clearReplyLog())
        assertTrue(store.state.value.replyLog.isEmpty())
        assertFalse(store.clearReplyLog())
    }
}
