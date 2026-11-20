package com.andharness.ru.data.tools.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ImageExtrasMath.svgTargetSize] — the target-size math behind
 * `image_svg_render`. No Android/Robolectric: the SVG render itself is device/runtime-only and is
 * verified on-device, not here.
 */
class ImageExtrasMathTest {

    @Test
    fun bothSidesGivenAreTakenVerbatim() {
        val size = ImageExtrasMath.svgTargetSize(320, 200, 100f, 100f)
        assertArrayEquals(intArrayOf(320, 200), size)
    }

    @Test
    fun widthOnlyPreservesAspectRatio() {
        // 200x100 intrinsic -> 2:1 -> width 400 implies height 200.
        val size = ImageExtrasMath.svgTargetSize(400, null, 200f, 100f)
        assertArrayEquals(intArrayOf(400, 200), size)
    }

    @Test
    fun heightOnlyPreservesAspectRatio() {
        // 200x100 intrinsic -> 2:1 -> height 50 implies width 100.
        val size = ImageExtrasMath.svgTargetSize(null, 50, 200f, 100f)
        assertArrayEquals(intArrayOf(100, 50), size)
    }

    @Test
    fun noSidesUsesIntrinsicSize() {
        val size = ImageExtrasMath.svgTargetSize(null, null, 640f, 480f)
        assertArrayEquals(intArrayOf(640, 480), size)
    }

    @Test
    fun noSidesAndTallIntrinsicKeepsRatio() {
        val size = ImageExtrasMath.svgTargetSize(null, null, 300f, 600f)
        assertArrayEquals(intArrayOf(300, 600), size)
    }

    @Test
    fun fallbackWhenIntrinsicSizeIsUnusable() {
        // AndroidSVG can report 0 for an SVG without width/height -> a square fallback is used.
        val size = ImageExtrasMath.svgTargetSize(null, null, 0f, 0f)
        assertArrayEquals(intArrayOf(1024, 1024), size)
    }

    @Test
    fun requestedSideIsClampedToTheMaximum() {
        val size = ImageExtrasMath.svgTargetSize(10_000, null, 100f, 100f)
        assertEquals(ImageExtrasMath.MAX_RENDER_SIDE, size[0])
        assertEquals(ImageExtrasMath.MAX_RENDER_SIDE, size[1])
    }

    @Test
    fun bothSidesAreClampedToTheMaximum() {
        val size = ImageExtrasMath.svgTargetSize(9000, 9000, 100f, 100f)
        assertArrayEquals(
            intArrayOf(ImageExtrasMath.MAX_RENDER_SIDE, ImageExtrasMath.MAX_RENDER_SIDE),
            size,
        )
    }

    @Test
    fun nonPositiveRequestIsRejected() {
        var threw = false
        try {
            ImageExtrasMath.svgTargetSize(0, null, 100f, 100f)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("zero width must be rejected", threw)
    }
}
