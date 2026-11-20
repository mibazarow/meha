package com.andharness.ru.domain.assist.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [OverlayBubbleLayout] — the clamp that keeps the overlay bubble reachable.
 *
 * The invariant is the same one the platform forces on gesture strokes: the bubble's top-left corner
 * must always be inside the screen, otherwise the user can drag it away and lose the only
 * affordance that can close it.
 */
class OverlayBubbleLayoutTest {

    private val screenW = 1080
    private val screenH = 2400
    private val size = 140

    @Test
    fun positionInsideScreenIsUnchanged() {
        assertEquals(
            BubblePosition(500, 900),
            OverlayBubbleLayout.clamp(500, 900, size, size, screenW, screenH),
        )
    }

    @Test
    fun negativeCoordinatesAreClampedToZero() {
        assertEquals(
            BubblePosition(0, 0),
            OverlayBubbleLayout.clamp(-500, -1, size, size, screenW, screenH),
        )
    }

    @Test
    fun overflowIsClampedToTheFarEdge() {
        assertEquals(
            BubblePosition(screenW - size, screenH - size),
            OverlayBubbleLayout.clamp(99_999, 99_999, size, size, screenW, screenH),
        )
    }

    @Test
    fun bubbleLargerThanTheScreenPinsToOrigin() {
        assertEquals(
            BubblePosition(0, 0),
            OverlayBubbleLayout.clamp(10, 10, screenW + 100, screenH + 100, screenW, screenH),
        )
    }

    @Test
    fun unknownScreenSizeStillAvoidsNegativeCoordinates() {
        assertEquals(
            BubblePosition(0, 0),
            OverlayBubbleLayout.clamp(-10, -10, size, size, screenWidth = 0, screenHeight = 0),
        )
        assertEquals(
            BubblePosition(42, 7),
            OverlayBubbleLayout.clamp(42, 7, size, size, screenWidth = -1, screenHeight = -1),
        )
    }

    @Test
    fun unknownBubbleSizeIsTreatedAsZero() {
        assertEquals(
            BubblePosition(screenW, screenH),
            OverlayBubbleLayout.clamp(
                x = 99_999,
                y = 99_999,
                bubbleWidth = 0,
                bubbleHeight = -5,
                screenWidth = screenW,
                screenHeight = screenH,
            ),
        )
    }

    @Test
    fun snapGoesToTheNearestEdgeKeepingY() {
        val left = OverlayBubbleLayout.snapToEdge(BubblePosition(10, 700), size, screenW)
        assertEquals(BubblePosition(0, 700), left)

        val right = OverlayBubbleLayout.snapToEdge(BubblePosition(1000, 700), size, screenW)
        assertEquals(BubblePosition(screenW - size, 700), right)
    }

    @Test
    fun snapUsesTheCentreNotTheLeftEdge() {
        // Centre = 540 + 70 = 610 > 540, so a bubble past the middle snaps right even though its
        // left edge is still in the left half.
        assertEquals(
            BubblePosition(screenW - size, 300),
            OverlayBubbleLayout.snapToEdge(BubblePosition(540, 300), size, screenW),
        )
    }

    @Test
    fun snapWithUnknownScreenKeepsOnlyANonNegativeX() {
        assertEquals(
            BubblePosition(0, 300),
            OverlayBubbleLayout.snapToEdge(BubblePosition(-40, 300), size, screenWidth = 0),
        )
    }
}
