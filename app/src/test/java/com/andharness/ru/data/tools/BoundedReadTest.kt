package com.andharness.ru.data.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * JVM tests for [readBounded]. Pure `java.io` only, no Android runtime.
 * Reference: https://developer.android.com/reference/java/io/InputStream
 */
class BoundedReadTest {

    @Test
    fun readsWholeStreamWhenUnderCap() {
        val data = "hello".toByteArray()
        val result = readBounded(ByteArrayInputStream(data), 1024L)
        assertArrayEquals(data, result.bytes)
        assertFalse(result.truncated)
    }

    @Test
    fun truncatesAndFlagsWhenOverCap() {
        val data = ByteArray(100) { it.toByte() }
        val result = readBounded(ByteArrayInputStream(data), 10L)
        assertEquals(10, result.bytes.size)
        assertArrayEquals(data.copyOf(10), result.bytes)
        assertTrue(result.truncated)
    }

    @Test
    fun exactCapIsNotTruncated() {
        val data = ByteArray(10) { it.toByte() }
        val result = readBounded(ByteArrayInputStream(data), 10L)
        assertEquals(10, result.bytes.size)
        assertFalse(result.truncated)
    }

    @Test
    fun emptyStreamYieldsEmptyNonTruncated() {
        val result = readBounded(ByteArrayInputStream(ByteArray(0)), 10L)
        assertEquals(0, result.bytes.size)
        assertFalse(result.truncated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveCap() {
        readBounded(ByteArrayInputStream(ByteArray(0)), 0L)
    }
}
