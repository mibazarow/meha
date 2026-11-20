package com.andharness.ru.data.tools.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [FileSplitSupport] — the pure size→parts math, part-name formatting/ordering and
 * byte-count parsing that `files_split` / `files_join` rely on. No Android runtime.
 */
class FileSplitSupportTest {

    // ---- equal-size plan -------------------------------------------------------------------

    @Test
    fun equalPartsSumToTotalAndDifferByAtMostOne() {
        assertEquals(listOf(4L, 3L, 3L), FileSplitSupport.equalPartSizes(10L, 3))
        assertEquals(10L, FileSplitSupport.equalPartSizes(10L, 3).sum())
    }

    @Test
    fun equalPartsSinglePartIsWholeFile() {
        assertEquals(listOf(10L), FileSplitSupport.equalPartSizes(10L, 1))
    }

    @Test
    fun equalPartsExactDivisionIsEven() {
        assertEquals(listOf(5L, 5L), FileSplitSupport.equalPartSizes(10L, 2))
    }

    @Test
    fun equalPartsEmptyFileIsEmptyPlan() {
        assertEquals(emptyList<Long>(), FileSplitSupport.equalPartSizes(0L, 3))
    }

    // ---- size-based plan -------------------------------------------------------------------

    @Test
    fun sizedPartsLastPartCarriesRemainder() {
        assertEquals(listOf(4L, 4L, 2L), FileSplitSupport.sizedPartSizes(10L, 4L))
        assertEquals(10L, FileSplitSupport.sizedPartSizes(10L, 4L).sum())
    }

    @Test
    fun sizedPartsExactDivisionOmitsRemainderPart() {
        assertEquals(listOf(4L, 4L, 4L), FileSplitSupport.sizedPartSizes(12L, 4L))
    }

    @Test
    fun sizedPartsPartBiggerThanFileYieldsSinglePart() {
        assertEquals(listOf(10L), FileSplitSupport.sizedPartSizes(10L, 100L))
    }

    @Test
    fun partCountForSizeRoundsUp() {
        assertEquals(3L, FileSplitSupport.partCountForSize(10L, 4L))
        assertEquals(3L, FileSplitSupport.partCountForSize(12L, 4L))
        assertEquals(0L, FileSplitSupport.partCountForSize(0L, 4L))
    }

    // ---- part naming / ordering ------------------------------------------------------------

    @Test
    fun digitsForPadsToAtLeastTwoDigits() {
        assertEquals(2, FileSplitSupport.digitsFor(3))
        assertEquals(2, FileSplitSupport.digitsFor(10))
        assertEquals(3, FileSplitSupport.digitsFor(100))
    }

    @Test
    fun partNameIsZeroPaddedWithTwoDigitsMinimum() {
        assertEquals("x.bin.part01", FileSplitSupport.partName("x.bin", 1, 3))
        assertEquals("x.bin.part03", FileSplitSupport.partName("x.bin", 3, 3))
        assertEquals("x.bin.part001", FileSplitSupport.partName("x.bin", 1, 100))
    }

    @Test
    fun partNamesAreOrderedByIndex() {
        assertEquals(
            listOf("a.part01", "a.part02", "a.part03"),
            FileSplitSupport.partNames("a", 3),
        )
    }

    @Test
    fun partIndexParsesTheNumericSuffix() {
        assertEquals(3, FileSplitSupport.partIndex("x.bin.part03"))
        assertEquals(12, FileSplitSupport.partIndex("x.part12"))
        assertNull(FileSplitSupport.partIndex("x.bin"))
        assertNull(FileSplitSupport.partIndex("x.part"))
    }

    @Test
    fun baseNameOfPartStripsTheSuffix() {
        assertEquals("x.bin", FileSplitSupport.baseNameOfPart("x.bin.part03"))
        assertNull(FileSplitSupport.baseNameOfPart("x.bin"))
        assertNull(FileSplitSupport.baseNameOfPart(".part03"))
    }

    @Test
    fun orderPartsIsNaturalByIndexNotLexicographic() {
        val ordered = FileSplitSupport.orderParts(listOf("a.part10", "a.part2", "a.part01"))
        assertEquals(listOf("a.part01", "a.part2", "a.part10"), ordered)
    }

    // ---- byte-count parsing ----------------------------------------------------------------

    @Test
    fun parseByteCountAcceptsPlainDigits() {
        assertEquals(104_857_600L, FileSplitSupport.parseByteCount("104857600"))
    }

    @Test
    fun parseByteCountAcceptsBinarySuffixesBothAlphabets() {
        val mib = 1024L * 1024L
        assertEquals(100L * mib, FileSplitSupport.parseByteCount("100MB"))
        assertEquals(100L * mib, FileSplitSupport.parseByteCount("100 MB"))
        assertEquals(100L * mib, FileSplitSupport.parseByteCount("100мб"))
        assertEquals(1024L, FileSplitSupport.parseByteCount("1КБ"))
        assertEquals(2L * 1024L * 1024L * 1024L, FileSplitSupport.parseByteCount("2GB"))
    }

    @Test
    fun parseByteCountRejectsGarbageAndZero() {
        assertNull(FileSplitSupport.parseByteCount(""))
        assertNull(FileSplitSupport.parseByteCount("abc"))
        assertNull(FileSplitSupport.parseByteCount("0"))
        assertNull(FileSplitSupport.parseByteCount("-5"))
        assertNull(FileSplitSupport.parseByteCount("10XB"))
    }

    @Test
    fun maxPartsIsAPositiveCeiling() {
        assertTrue(FileSplitSupport.MAX_PARTS > 0)
    }
}
