package com.andharness.ru.domain.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [GestureBounds], the pure clamp behind `ui_gesture_multi`.
 *
 * They lock in the BUG-024 fix: the computed stroke coordinates must always stay inside
 * `0..width-1` x `0..height-1` so `GestureDescription.StrokeDescription` can never throw
 * "Path bounds must not be negative".
 */
class GestureBoundsTest {

    private val width = 1080
    private val height = 2400

    @Test
    fun normalPinchKeepsRequestedSpans() {
        val clamped = GestureBounds.clamp(
            cx = 540f,
            cy = 1000f,
            startDistance = 200f,
            endDistance = 400f,
            width = width,
            height = height,
        )
        assertNotNull(clamped)
        clamped!!
        assertEquals(440, clamped.left.startX)
        assertEquals(340, clamped.left.endX)
        assertEquals(640, clamped.right.startX)
        assertEquals(740, clamped.right.endX)
        assertEquals(1000, clamped.left.y)
        assertEquals(200, clamped.startSpan)
        assertEquals(400, clamped.endSpan)
        assertTrue(clamped.exact)
        assertInside(clamped)
    }

    /** BUG-024: `end_distance = 800` at `cx = 360` used to put the left point at x = -40. */
    @Test
    fun endDistanceBeyondLeftEdgeIsClampedInsteadOfNegative() {
        val clamped = GestureBounds.clamp(
            cx = 360f,
            cy = 800f,
            startDistance = 200f,
            endDistance = 800f,
            width = width,
            height = height,
        )
        assertNotNull(clamped)
        clamped!!
        assertEquals(0, clamped.left.endX)
        assertEquals(200, clamped.startSpan)
        assertEquals(760, clamped.endSpan)
        assertFalse(clamped.exact)
        assertInside(clamped)
    }

    @Test
    fun negativeDistancesCollapseWithoutThrowing() {
        val clamped = GestureBounds.clamp(
            cx = 500f,
            cy = 500f,
            startDistance = -100f,
            endDistance = -50f,
            width = width,
            height = height,
        )
        assertNotNull(clamped)
        clamped!!
        assertEquals(0, clamped.startSpan)
        assertEquals(0, clamped.endSpan)
        assertFalse(clamped.exact)
        assertInside(clamped)
    }

    @Test
    fun zeroDistancesAreDegenerateButBounded() {
        val clamped = GestureBounds.clamp(
            cx = 500f,
            cy = 500f,
            startDistance = 0f,
            endDistance = 0f,
            width = width,
            height = height,
        )
        assertNotNull(clamped)
        clamped!!
        assertEquals(500, clamped.left.startX)
        assertEquals(500, clamped.left.endX)
        assertEquals(500, clamped.right.startX)
        assertEquals(500, clamped.right.endX)
        assertEquals(0, clamped.startSpan)
        assertEquals(0, clamped.endSpan)
        assertInside(clamped)
    }

    @Test
    fun centreBeyondTopLeftEdgeIsClamped() {
        val clamped = GestureBounds.clamp(
            cx = -500f,
            cy = -500f,
            startDistance = 200f,
            endDistance = 400f,
            width = width,
            height = height,
        )
        assertNotNull(clamped)
        clamped!!
        assertEquals(0, clamped.left.startX)
        assertEquals(0, clamped.left.endX)
        assertEquals(0, clamped.right.startX)
        assertEquals(0, clamped.right.endX)
        assertEquals(0, clamped.left.y)
        assertFalse(clamped.exact)
        assertInside(clamped)
    }

    @Test
    fun centreBeyondBottomRightEdgeIsClamped() {
        val clamped = GestureBounds.clamp(
            cx = 99_999f,
            cy = 99_999f,
            startDistance = 200f,
            endDistance = 400f,
            width = width,
            height = height,
        )
        assertNotNull(clamped)
        clamped!!
        assertEquals(width - 1, clamped.left.startX)
        assertEquals(width - 1, clamped.right.startX)
        assertEquals(height - 1, clamped.left.y)
        assertInside(clamped)
    }

    @Test
    fun unknownDisplaySizeReturnsNull() {
        assertNull(GestureBounds.clamp(500f, 500f, 200f, 400f, width = 0, height = height))
        assertNull(GestureBounds.clamp(500f, 500f, 200f, 400f, width = width, height = 0))
        assertNull(GestureBounds.clamp(500f, 500f, 200f, 400f, width = -1, height = -1))
    }

    @Test
    fun clampSpanStaysInsideScreenBounds() {
        assertEquals(440 to 640, GestureBounds.clampSpan(cx = 540f, span = 200f, width = width))
        assertEquals(0 to 760, GestureBounds.clampSpan(cx = 360f, span = 800f, width = width))
        assertEquals(540 to 540, GestureBounds.clampSpan(cx = 540f, span = -40f, width = width))
        assertEquals(0 to 0, GestureBounds.clampSpan(cx = 540f, span = 200f, width = 0))
    }

    @Test
    fun clampCoordinateRoundsAndBounds() {
        assertEquals(0, GestureBounds.clampCoordinate(-1f, width))
        assertEquals(1079, GestureBounds.clampCoordinate(5000f, width))
        assertEquals(0, GestureBounds.clampCoordinate(10f, size = 0))
        assertEquals(0, GestureBounds.clampCoordinate(Float.NaN, width))
        assertEquals(0, GestureBounds.clampCoordinate(0.4f, width))
        assertEquals(1, GestureBounds.clampCoordinate(0.6f, width))
    }

    /** The invariant the platform enforces: no computed coordinate may be negative. */
    private fun assertInside(clamped: GestureBounds.Clamped) {
        val xs = listOf(
            clamped.left.startX,
            clamped.left.endX,
            clamped.right.startX,
            clamped.right.endX,
        )
        assertTrue("negative x: $xs", xs.all { it >= 0 })
        assertTrue("x beyond width: $xs", xs.all { it < width })
        assertTrue("negative y", clamped.left.y >= 0)
        assertTrue("y beyond height", clamped.left.y < height)
        assertEquals(clamped.left.y, clamped.right.y)
        assertTrue("negative span", clamped.startSpan >= 0 && clamped.endSpan >= 0)
    }
}
