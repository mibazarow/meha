package com.andharness.ru.domain.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [MessageExternalRefs]: the dedup keys must be stable for the same source event
 * and distinct for different ones, and must never embed a full message body.
 */
class MessageExternalRefsTest {

    @Test
    fun liveSmsKeyIsStableForTheSameBroadcast() {
        val a = MessageExternalRefs.smsLive("+79990001122", 1_700_000_000_000, "привет")
        val b = MessageExternalRefs.smsLive("+79990001122", 1_700_000_000_000, "привет")
        assertEquals(a, b)
        assertTrue(a.startsWith("sms:live:"))
    }

    @Test
    fun liveSmsKeyDiffersForDifferentSenderTimeOrBody() {
        val base = MessageExternalRefs.smsLive("+79990001122", 1, "hi")
        assertNotEquals(base, MessageExternalRefs.smsLive("+79990001123", 1, "hi"))
        assertNotEquals(base, MessageExternalRefs.smsLive("+79990001122", 2, "hi"))
        assertNotEquals(base, MessageExternalRefs.smsLive("+79990001122", 1, "hi!"))
    }

    @Test
    fun notificationKeyIsStableForTheSameMessage() {
        val a = MessageExternalRefs.notification("org.telegram.messenger", "key1", 0, "привет", 42)
        val b = MessageExternalRefs.notification("org.telegram.messenger", "key1", 0, "привет", 42)
        assertEquals(a, b)
        assertTrue(a.startsWith("notif:org.telegram.messenger:key1:0:"))
    }

    @Test
    fun notificationKeyDiffersOnPackageKeyIndexTextOrTime() {
        val base = MessageExternalRefs.notification("pkg", "k", 0, "hi", 1)
        assertNotEquals(base, MessageExternalRefs.notification("pkg2", "k", 0, "hi", 1))
        assertNotEquals(base, MessageExternalRefs.notification("pkg", "k2", 0, "hi", 1))
        assertNotEquals(base, MessageExternalRefs.notification("pkg", "k", 1, "hi", 1))
        assertNotEquals(base, MessageExternalRefs.notification("pkg", "k", 0, "hi!", 1))
        assertNotEquals(base, MessageExternalRefs.notification("pkg", "k", 0, "hi", 2))
    }

    @Test
    fun shortHashIsLowercaseHexOfAFixedShortLength() {
        val hash = MessageExternalRefs.shortHash("some input")
        assertEquals(24, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun shortHashDistinguishesInputsButIsDeterministic() {
        assertEquals(
            MessageExternalRefs.shortHash("a"),
            MessageExternalRefs.shortHash("a")
        )
        assertNotEquals(
            MessageExternalRefs.shortHash("a"),
            MessageExternalRefs.shortHash("b")
        )
    }
}
