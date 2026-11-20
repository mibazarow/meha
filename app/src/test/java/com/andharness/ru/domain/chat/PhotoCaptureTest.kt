package com.andharness.ru.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCaptureTest {

    @Test
    fun tempFileNameHasCapturePrefixTimestampAndJpgExtension() {
        val name = PhotoCapture.tempFileName(1_700_000_000_000L)
        assertEquals("meha_capture_1700000000000.jpg", name)
        assertTrue(name.startsWith(PhotoCapture.FILE_PREFIX))
        assertTrue(name.endsWith(".jpg"))
    }

    @Test
    fun tempFileNamesAreUniquePerTimestamp() {
        assertNotEquals(
            PhotoCapture.tempFileName(1L),
            PhotoCapture.tempFileName(2L)
        )
    }

    @Test
    fun displayNameIsReadableAndKeepsTheJpgExtension() {
        val display = PhotoCapture.displayName(PhotoCapture.tempFileName(1234L))
        assertEquals("Фото 1234.jpg", display)
        assertTrue(display.endsWith(".jpg"))
    }

    @Test
    fun successfulCaptureImportsAsJpegImageViaSharedPolicy() {
        val result = PhotoCapture.resolve(
            success = true,
            fileName = PhotoCapture.tempFileName(42L),
            sizeBytes = 512_000L,
            existingCount = 0
        )
        val imported = result as PhotoCaptureResult.Imported
        assertEquals("Фото 42.jpg", imported.name)
        assertEquals("image/jpeg", imported.mime)
        assertEquals(AttachmentKind.IMAGE, imported.kind)
        assertEquals(512_000L, imported.sizeBytes)
        assertTrue(PhotoCapture.shouldAttach(result))
    }

    @Test
    fun cancelProducesNoAttachment() {
        val result = PhotoCapture.resolve(
            success = false,
            fileName = PhotoCapture.tempFileName(7L),
            sizeBytes = 0L,
            existingCount = 0
        )
        assertEquals(PhotoCaptureResult.Cancelled, result)
        assertFalse(PhotoCapture.shouldAttach(result))
    }

    @Test
    fun missingFileNameProducesNoAttachment() {
        val result = PhotoCapture.resolve(
            success = true,
            fileName = null,
            sizeBytes = 1000L,
            existingCount = 0
        )
        assertEquals(PhotoCaptureResult.Cancelled, result)
        assertFalse(PhotoCapture.shouldAttach(result))
    }

    @Test
    fun emptyCapturedFileProducesNoAttachment() {
        val result = PhotoCapture.resolve(
            success = true,
            fileName = PhotoCapture.tempFileName(9L),
            sizeBytes = 0L,
            existingCount = 0
        )
        assertEquals(PhotoCaptureResult.Cancelled, result)
        assertFalse(PhotoCapture.shouldAttach(result))
    }

    @Test
    fun tooManyAlreadyStagedIsRejectedWithNotice() {
        val result = PhotoCapture.resolve(
            success = true,
            fileName = PhotoCapture.tempFileName(11L),
            sizeBytes = 1000L,
            existingCount = AttachmentPolicy.MAX_ATTACHMENTS
        )
        val rejected = result as PhotoCaptureResult.Rejected
        assertTrue(rejected.notice.contains(AttachmentPolicy.MAX_ATTACHMENTS.toString()))
        assertFalse(PhotoCapture.shouldAttach(result))
    }

    @Test
    fun oversizedCaptureIsRejectedWithNotice() {
        val result = PhotoCapture.resolve(
            success = true,
            fileName = PhotoCapture.tempFileName(13L),
            sizeBytes = AttachmentPolicy.MAX_SIZE_BYTES + 1,
            existingCount = 0
        )
        val rejected = result as PhotoCaptureResult.Rejected
        assertTrue(rejected.notice.contains(AttachmentPolicy.humanSize(AttachmentPolicy.MAX_SIZE_BYTES)))
        assertFalse(PhotoCapture.shouldAttach(result))
    }
}
