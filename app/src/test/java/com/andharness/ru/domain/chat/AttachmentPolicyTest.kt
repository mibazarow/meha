package com.andharness.ru.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPolicyTest {

    private fun attachment(
        name: String,
        mime: String,
        kind: AttachmentKind,
        path: String = "/storage/emulated/0/attachments/$name"
    ) = ChatAttachment(path = path, name = name, mime = mime, kind = kind)

    @Test
    fun classifiesImagesByMimeAndByExtension() {
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.classify("image/jpeg", "photo.jpg"))
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.classify("image/png", "scan.PNG"))
        // Some providers report octet-stream for images; the extension must still win.
        assertEquals(
            AttachmentKind.IMAGE,
            AttachmentPolicy.classify("application/octet-stream", "IMG_0001.HEIC")
        )
    }

    @Test
    fun classifiesDocumentsByMimeAndByExtension() {
        assertEquals(
            AttachmentKind.DOCUMENT,
            AttachmentPolicy.classify("application/pdf", "report.pdf")
        )
        assertEquals(
            AttachmentKind.DOCUMENT,
            AttachmentPolicy.classify("application/octet-stream", "notes.txt")
        )
        assertEquals(
            AttachmentKind.DOCUMENT,
            AttachmentPolicy.classify("text/csv", "data.csv")
        )
    }

    @Test
    fun classifiesUnknownBinaryAsOther() {
        assertEquals(
            AttachmentKind.OTHER,
            AttachmentPolicy.classify("application/zip", "archive.bin")
        )
        assertEquals(AttachmentKind.OTHER, AttachmentPolicy.classify("", "noextension"))
    }

    @Test
    fun enforcesAttachmentCountLimit() {
        assertEquals(
            AttachmentLimit.Ok,
            AttachmentPolicy.validate(currentCount = AttachmentPolicy.MAX_ATTACHMENTS - 1, sizeBytes = 1)
        )
        val tooMany = AttachmentPolicy.validate(currentCount = AttachmentPolicy.MAX_ATTACHMENTS, sizeBytes = 1)
        assertTrue(tooMany is AttachmentLimit.TooMany)
        assertEquals(AttachmentPolicy.MAX_ATTACHMENTS, (tooMany as AttachmentLimit.TooMany).max)
    }

    @Test
    fun enforcesFileSizeLimit() {
        assertEquals(
            AttachmentLimit.Ok,
            AttachmentPolicy.validate(currentCount = 0, sizeBytes = AttachmentPolicy.MAX_SIZE_BYTES)
        )
        val tooLarge = AttachmentPolicy.validate(
            currentCount = 0,
            sizeBytes = AttachmentPolicy.MAX_SIZE_BYTES + 1
        )
        assertTrue(tooLarge is AttachmentLimit.TooLarge)
        assertEquals(AttachmentPolicy.MAX_SIZE_BYTES, (tooLarge as AttachmentLimit.TooLarge).maxBytes)
        // Unknown size (-1) must never block adding the attachment.
        assertEquals(AttachmentLimit.Ok, AttachmentPolicy.validate(currentCount = 0, sizeBytes = -1))
    }

    @Test
    fun humanSizeRendersRussianUnits() {
        assertEquals("512 Б", AttachmentPolicy.humanSize(512))
        assertEquals("1 КБ", AttachmentPolicy.humanSize(1024))
        assertEquals("1.5 КБ", AttachmentPolicy.humanSize(1536))
        assertEquals("20 МБ", AttachmentPolicy.humanSize(AttachmentPolicy.MAX_SIZE_BYTES))
    }

    @Test
    fun sampleSizeReducesLongestSide() {
        // A large photo must not be decoded at full resolution for a 100 px preview.
        assertEquals(32, AttachmentPolicy.sampleSize(width = 4000, height = 3000, target = 100))
        // Already small enough: decode as-is.
        assertEquals(1, AttachmentPolicy.sampleSize(width = 80, height = 60, target = 100))
        // Degenerate input is handled without dividing by zero.
        assertEquals(1, AttachmentPolicy.sampleSize(width = 0, height = 0, target = 100))
    }

    @Test
    fun scaledSizeCapsTheLongestSide() {
        assertEquals(1024 to 768, AttachmentPolicy.scaledSize(width = 4000, height = 3000, max = 1024))
        assertEquals(600 to 400, AttachmentPolicy.scaledSize(width = 600, height = 400, max = 1024))
        // Never scales to zero pixels.
        val (w, h) = AttachmentPolicy.scaledSize(width = 4000, height = 1, max = 1024)
        assertTrue(w <= 1024 && w >= 1 && h >= 1)
    }

    @Test
    fun buildUserTextIncludesNameAndReadablePath() {
        val text = AttachmentPolicy.buildUserText(
            text = "что тут написано?",
            attachments = listOf(
                attachment("photo.jpg", "image/jpeg", AttachmentKind.IMAGE, "/root/attachments/1_photo.jpg"),
                attachment("report.pdf", "application/pdf", AttachmentKind.DOCUMENT, "/root/attachments/2_report.pdf")
            )
        )
        assertTrue(text.startsWith("что тут написано?"))
        assertTrue(text.contains("photo.jpg"))
        assertTrue(text.contains("/root/attachments/1_photo.jpg"))
        assertTrue(text.contains("report.pdf"))
        assertTrue(text.contains("/root/attachments/2_report.pdf"))
        assertTrue(text.contains("[Вложения: 2]"))
    }

    @Test
    fun buildUserTextWithoutAttachmentsReturnsOriginalText() {
        assertEquals("привет", AttachmentPolicy.buildUserText("привет", emptyList()))
    }

    @Test
    fun buildUserTextForAttachmentOnlyMessageIsNotBlank() {
        val text = AttachmentPolicy.buildUserText(
            text = "",
            attachments = listOf(attachment("scan.png", "image/png", AttachmentKind.IMAGE))
        )
        assertTrue(text.isNotBlank())
        assertTrue(text.contains("scan.png"))
    }
}
