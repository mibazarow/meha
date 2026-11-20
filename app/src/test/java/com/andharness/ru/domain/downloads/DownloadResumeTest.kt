package com.andharness.ru.domain.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for the resumable-download HTTP Range helpers: the `Range` header and resolution of the
 * remote total from `Content-Range` / `Content-Length`. No network involved.
 */
class DownloadResumeTest {

    @Test
    fun noRangeHeaderWithoutExistingBytes() {
        assertNull(DownloadResume.rangeHeader(0L))
        assertNull(DownloadResume.rangeHeader(-1L))
    }

    @Test
    fun rangeHeaderStartsAtExistingLength() {
        assertEquals("bytes=100-", DownloadResume.rangeHeader(100L))
        assertEquals("bytes=1280835840-", DownloadResume.rangeHeader(1_280_835_840L))
    }

    @Test
    fun parsesTotalFromContentRange() {
        assertEquals(
            1_280_835_840L,
            DownloadResume.parseContentRangeTotal("bytes 0-1/1280835840"),
        )
    }

    @Test
    fun parsesTotalWithUnknownStart() {
        assertEquals(123L, DownloadResume.parseContentRangeTotal("bytes */123"))
    }

    @Test
    fun unknownContentRangeTotalIsNull() {
        assertNull(DownloadResume.parseContentRangeTotal("bytes 0-1/*"))
        assertNull(DownloadResume.parseContentRangeTotal("not-a-range"))
        assertNull(DownloadResume.parseContentRangeTotal("bytes 0-1/"))
    }

    @Test
    fun contentRangeTotalWins() {
        assertEquals(
            500L,
            DownloadResume.resolveTotal("bytes 100-499/500", offset = 100L, contentLength = 400L, fallback = 999L),
        )
    }

    @Test
    fun fallsBackToOffsetPlusContentLength() {
        assertEquals(
            700L,
            DownloadResume.resolveTotal(contentRange = null, offset = 300L, contentLength = 400L, fallback = 999L),
        )
    }

    @Test
    fun fallsBackToSpecWhenLengthUnknown() {
        assertEquals(
            1_280_835_840L,
            DownloadResume.resolveTotal(
                contentRange = null,
                offset = 0L,
                contentLength = -1L,
                fallback = 1_280_835_840L,
            ),
        )
    }

    @Test
    fun zeroContentLengthUsesFallback() {
        assertEquals(
            42L,
            DownloadResume.resolveTotal(contentRange = null, offset = 7L, contentLength = 0L, fallback = 42L),
        )
    }
}
