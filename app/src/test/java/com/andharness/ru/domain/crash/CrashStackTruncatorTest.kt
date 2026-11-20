package com.andharness.ru.domain.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the crash-stack cap (update.md §1.4). */
class CrashStackTruncatorTest {

    @Test
    fun shortStackIsReturnedUnchanged() {
        val stack = "java.lang.IllegalStateException: boom\n\tat a.b.C.d(C.kt:1)"
        assertEquals(stack, CrashStackTruncator.truncate(stack))
    }

    @Test
    fun nullStackBecomesAnEmptyString() {
        assertEquals("", CrashStackTruncator.truncate(null))
    }

    @Test
    fun stackAtExactlyTheCapIsNotMarkedAsTruncated() {
        val stack = "x".repeat(CrashStackTruncator.MAX_CHARS)
        val result = CrashStackTruncator.truncate(stack)
        assertEquals(CrashStackTruncator.MAX_CHARS, result.length)
        assertEquals(stack, result)
    }

    @Test
    fun longStackIsTruncatedToTheCapAndMarked() {
        val stack = "x".repeat(CrashStackTruncator.MAX_CHARS * 3)
        val result = CrashStackTruncator.truncate(stack)

        assertEquals(CrashStackTruncator.MAX_CHARS, result.length)
        assertTrue("truncation must be explicit", result.contains("[stack truncated]"))
        assertTrue(result.startsWith("x".repeat(100)))
    }

    @Test
    fun customCapIsRespected() {
        val stack = "abcdefghij".repeat(10)
        val result = CrashStackTruncator.truncate(stack, maxChars = 32)
        assertEquals(32, result.length)
        assertTrue(result.contains("[stack truncated]"))
    }

    @Test
    fun nonPositiveCapYieldsAnEmptyString() {
        assertEquals("", CrashStackTruncator.truncate("boom", maxChars = 0))
        assertEquals("", CrashStackTruncator.truncate("boom", maxChars = -5))
    }
}
