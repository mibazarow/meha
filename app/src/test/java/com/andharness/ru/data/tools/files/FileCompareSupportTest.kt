package com.andharness.ru.data.tools.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for [FileCompareSupport] — byte-level first-difference (in-memory and streaming) and
 * the bounded text summary. Pure `java.io`, no Android runtime.
 */
class FileCompareSupportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray) =
        temp.newFile(name).apply { writeBytes(bytes) }

    // ---- firstDifference (in-memory) -------------------------------------------------------

    @Test
    fun identicalArraysHaveNoDifference() {
        assertNull(FileCompareSupport.firstDifference("abc".toByteArray(), "abc".toByteArray()))
    }

    @Test
    fun differenceAtFirstByteIsZero() {
        assertEquals(0L, FileCompareSupport.firstDifference("abc".toByteArray(), "zbc".toByteArray()))
    }

    @Test
    fun differenceReportsTheExactOffset() {
        assertEquals(2L, FileCompareSupport.firstDifference("abc".toByteArray(), "abz".toByteArray()))
    }

    @Test
    fun prefixShorterArrayReportsItsLength() {
        assertEquals(3L, FileCompareSupport.firstDifference("abc".toByteArray(), "abcd".toByteArray()))
    }

    // ---- firstDifference (streaming, crossing the buffer) ----------------------------------

    @Test
    fun streamingIdenticalLargeFilesHaveNoDifference() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        assertNull(FileCompareSupport.firstDifference(file("a.bin", bytes), file("b.bin", bytes.copyOf())))
    }

    @Test
    fun streamingDifferenceBeyondOneBufferIsFound() {
        val left = ByteArray(200_000) { (it % 251).toByte() }
        val right = left.copyOf().also { it[70_000] = (it[70_000] + 1).toByte() }
        assertEquals(70_000L, FileCompareSupport.firstDifference(file("a.bin", left), file("b.bin", right)))
    }

    @Test
    fun streamingPrefixFileReportsShorterLength() {
        val left = ByteArray(200_000) { (it % 251).toByte() }
        val right = left.copyOf() + ByteArray(10)
        assertEquals(200_000L, FileCompareSupport.firstDifference(file("a.bin", left), file("b.bin", right)))
    }

    // ---- firstDifferingLine ----------------------------------------------------------------

    @Test
    fun identicalLineListsHaveNoDifference() {
        assertNull(FileCompareSupport.firstDifferingLine(listOf("a", "b"), listOf("a", "b")))
    }

    @Test
    fun firstDifferingLineReportsIndex() {
        assertEquals(1, FileCompareSupport.firstDifferingLine(listOf("a", "b", "c"), listOf("a", "x", "c")))
    }

    @Test
    fun linePrefixReportsShorterLength() {
        assertEquals(2, FileCompareSupport.firstDifferingLine(listOf("a", "b"), listOf("a", "b", "c")))
    }

    // ---- unifiedSummary --------------------------------------------------------------------

    @Test
    fun identicalListsProduceEmptySummary() {
        assertEquals("", FileCompareSupport.unifiedSummary(listOf("a", "b"), listOf("a", "b")))
    }

    @Test
    fun summaryMarksRemovedAndAddedLines() {
        val diff = FileCompareSupport.unifiedSummary(listOf("a", "old", "c"), listOf("a", "new", "c"))
        assertTrue(diff, diff.contains("- old"))
        assertTrue(diff, diff.contains("+ new"))
        assertTrue(diff, diff.contains("  a"))
    }

    @Test
    fun summaryIsBoundedByMaxLines() {
        val a = (1..500).map { "line $it" }
        val b = a.toMutableList().also { it[250] = "changed" }
        val diff = FileCompareSupport.unifiedSummary(a, b, maxLines = 5)
        assertTrue("summary must be bounded: ${diff.lines().size}", diff.lines().size <= 6)
    }

    @Test
    fun nonPositiveMaxLinesProducesEmptySummary() {
        assertEquals("", FileCompareSupport.unifiedSummary(listOf("a"), listOf("b"), maxLines = 0))
    }
}
