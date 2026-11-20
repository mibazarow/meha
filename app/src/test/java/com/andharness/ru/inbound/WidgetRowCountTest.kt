package com.andharness.ru.inbound

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the widget's row-count math. The helper takes plain `Float` dp (no Android/Compose
 * types), so these run on the JVM and pin the counts the composition derives from `LocalSize`.
 *
 * The 3x2 default on the test launcher is ≈223dp of bound height; the declared metadata minimum is
 * 110dp (what `SizeMode.Single` used to report).
 */
class WidgetRowCountTest {

    @Test
    fun `the 3x2 default height shows four rows`() {
        assertEquals(4, visibleRowCountForHeight(223f, total = 20))
    }

    @Test
    fun `an unknown size defaults to three rows instead of collapsing to one`() {
        assertEquals(3, visibleRowCountForHeight(0f, total = 20))
        assertEquals(3, visibleRowCountForHeight(-1f, total = 20))
    }

    @Test
    fun `the declared minimum height clamps to one row`() {
        assertEquals(1, visibleRowCountForHeight(110f, total = 20))
    }

    @Test
    fun `a height too small for a single row still shows one`() {
        assertEquals(1, visibleRowCountForHeight(40f, total = 20))
    }

    @Test
    fun `resizing larger grows up to five rows`() {
        assertEquals(4, visibleRowCountForHeight(240f, total = 20))
        assertEquals(5, visibleRowCountForHeight(320f, total = 20))
        assertEquals(5, visibleRowCountForHeight(2000f, total = 20))
    }

    @Test
    fun `the row count never exceeds the number of chats`() {
        assertEquals(2, visibleRowCountForHeight(223f, total = 2))
        assertEquals(0, visibleRowCountForHeight(223f, total = 0))
    }

    @Test
    fun `the unknown-size default is capped by the number of chats`() {
        assertEquals(2, visibleRowCountForHeight(0f, total = 2))
    }
}
