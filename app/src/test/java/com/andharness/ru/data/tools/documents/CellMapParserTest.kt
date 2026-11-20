package com.andharness.ru.data.tools.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [CellMapParser]: the two accepted `cells` JSON shapes, reference normalisation,
 * value stringification and honest rejection of malformed input.
 *
 * References:
 * - SpreadsheetML cell reference — https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/spreadsheetml
 */
class CellMapParserTest {

    @Test
    fun parsesAnObjectMapAndNormalisesReferences() {
        val cells = CellMapParser.parse("""{"a1": "x", "B2": 42}""")
        assertEquals(listOf("A1", "B2"), cells.map { it.ref })
        assertEquals(listOf("x", "42"), cells.map { it.value })
    }

    @Test
    fun parsesAnArrayOfObjects() {
        val cells = CellMapParser.parse("""[{"cell":"C3","value":"v"},{"cell":"D4","value":7}]""")
        assertEquals(listOf("C3", "D4"), cells.map { it.ref })
        assertEquals(listOf("v", "7"), cells.map { it.value })
    }

    @Test
    fun stringifiesNestedValues() {
        val cells = CellMapParser.parse("""{"A1":[1,2],"A2":{"k":"v"},"A3":null}""")
        assertEquals("1, 2", cells.first { it.ref == "A1" }.value)
        assertEquals("k=v", cells.first { it.ref == "A2" }.value)
        assertEquals("", cells.first { it.ref == "A3" }.value)
    }

    @Test
    fun rejectsAnInvalidCellReference() {
        val error = runCatching { CellMapParser.parse("""{"1A": "x"}""") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("1A"))
    }

    @Test
    fun rejectsAnEmptyMap() {
        val error = runCatching { CellMapParser.parse("{}") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsANonObjectOrArray() {
        val error = runCatching { CellMapParser.parse(""""A1"""") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsMalformedJson() {
        val error = runCatching { CellMapParser.parse("{not json") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsAnArrayEntryWithoutCell() {
        val error = runCatching {
            CellMapParser.parse("""[{"value":"x"}]""")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
