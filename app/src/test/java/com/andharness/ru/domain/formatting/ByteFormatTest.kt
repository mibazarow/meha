package com.andharness.ru.domain.formatting

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for [ByteFormat.humanBytes]: deterministic Russian byte sizes for UI copy. */
class ByteFormatTest {

    @Test
    fun humanBytesMegabytes() {
        assertEquals("512 МБ", ByteFormat.humanBytes(512L * 1024L * 1024L))
    }

    @Test
    fun humanBytesGigabytesWithOneDecimal() {
        assertEquals("1,0 ГБ", ByteFormat.humanBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun humanBytesZero() {
        assertEquals("0 МБ", ByteFormat.humanBytes(0L))
    }
}
