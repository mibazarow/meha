package com.andharness.ru.data.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Pins the read-until-EOF semantics used by [SshClient] to drain a JSch `ChannelExec` stdout.
 *
 * Regression guard for BUG-025: reading only while `available() > 0` (or a single `read()`) drops
 * the output of short/fast commands. [drainToEof] is deliberately Android-free so this runs on the
 * JVM; the JSch wiring itself still needs a real SSH server.
 */
class StreamDrainTest {

    @Test
    fun readsEverythingUntilEof() {
        val payload = "meha-ok\n".repeat(1_000).toByteArray()
        val out = ByteArrayOutputStream()

        val total = drainToEof(ByteArrayInputStream(payload), out)

        assertEquals(payload.size.toLong(), total)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun emptyStreamCopiesNothing() {
        val out = ByteArrayOutputStream()

        assertEquals(0L, drainToEof(ByteArrayInputStream(ByteArray(0)), out))
        assertEquals(0, out.size())
    }

    @Test
    fun doesNotRelyOnAvailable() {
        // available() is always 0 while read() still yields bytes: an available()-polling loop
        // would return before copying anything. This is the BUG-025 shape.
        val payload = "short\n".toByteArray()
        val stream = object : InputStream() {
            private var pos = 0
            override fun available(): Int = 0
            override fun read(): Int =
                if (pos < payload.size) payload[pos++].toInt() and 0xFF else -1
        }
        val out = ByteArrayOutputStream()

        assertEquals(payload.size.toLong(), drainToEof(stream, out))
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun copiesCorrectlyWithATinyBuffer() {
        val payload = "abcdef".toByteArray()
        val out = ByteArrayOutputStream()

        assertEquals(payload.size.toLong(), drainToEof(ByteArrayInputStream(payload), out, bufferSize = 1))
        assertArrayEquals(payload, out.toByteArray())
    }
}
