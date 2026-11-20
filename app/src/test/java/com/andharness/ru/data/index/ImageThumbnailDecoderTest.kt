package com.andharness.ru.data.index

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for the downscale sizing used before embedding: `inSampleSize` must be a power of two
 * that brings the longer side of an image to just at/above the target side without decoding full size.
 */
class ImageThumbnailDecoderTest {

    @Test
    fun largeImageIsDownscaledByPowerOfTwo() {
        // 4000 -> 2000 -> 1000 (still >= 512), next halving would be 500 < 512, so sample = 4.
        assertEquals(4, ImageThumbnailDecoder.sampleSizeFor(width = 4000, height = 3000, maxSide = 512))
    }

    @Test
    fun imageAtTwiceTheTargetHalvesOnce() {
        assertEquals(2, ImageThumbnailDecoder.sampleSizeFor(width = 1024, height = 768, maxSide = 512))
    }

    @Test
    fun imageAlreadyWithinTargetIsNotDownscaled() {
        assertEquals(1, ImageThumbnailDecoder.sampleSizeFor(width = 600, height = 400, maxSide = 512))
    }

    @Test
    fun smallImageIsNotDownscaled() {
        assertEquals(1, ImageThumbnailDecoder.sampleSizeFor(width = 100, height = 100, maxSide = 512))
    }

    @Test
    fun portraitImageUsesItsLongerSide() {
        assertEquals(4, ImageThumbnailDecoder.sampleSizeFor(width = 3000, height = 4000, maxSide = 512))
    }

    @Test
    fun invalidDimensionsOrLimitYieldOne() {
        assertEquals(1, ImageThumbnailDecoder.sampleSizeFor(width = 0, height = 4000, maxSide = 512))
        assertEquals(1, ImageThumbnailDecoder.sampleSizeFor(width = 4000, height = 0, maxSide = 512))
        assertEquals(1, ImageThumbnailDecoder.sampleSizeFor(width = 4000, height = 3000, maxSide = 0))
    }
}
