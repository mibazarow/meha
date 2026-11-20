package com.andharness.ru.data.tools.documents

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure merge/split helpers in [OfficeMerge].
 */
class OfficeMergeTest {

    @Test
    fun mergeTextsJoinsWithSeparator() {
        assertEquals("a\n\nb", OfficeMerge.mergeTexts(listOf("a", "b"), "\n\n"))
        assertEquals("a---b", OfficeMerge.mergeTexts(listOf("a", "b"), "---"))
    }

    @Test
    fun mergeRowsAppendsSourcesWithMarkers() {
        val merged = OfficeMerge.mergeRows(
            listOf(listOf(listOf("a"), listOf("b")), listOf(listOf("c"))),
            markers = listOf(null, "=== f2 ===")
        )
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("=== f2 ==="), listOf("c")), merged)
    }

    @Test
    fun mergeRowsWithoutMarkersIsPlainConcatenation() {
        val merged = OfficeMerge.mergeRows(
            listOf(listOf(listOf("a")), listOf(listOf("b")))
        )
        assertEquals(listOf(listOf("a"), listOf("b")), merged)
    }

    @Test
    fun splitTextSplitsOnSeparatorLine() {
        val parts = OfficeMerge.splitText("part1\n---\npart2\n---\npart3", "---")
        assertEquals(listOf("part1", "part2", "part3"), parts)
    }

    @Test
    fun splitTextDropsEmptyPartsAndDefaultsSeparator() {
        assertEquals(listOf("a", "b"), OfficeMerge.splitText("---\na\n---\n\n---\nb\n---", ""))
    }
}
