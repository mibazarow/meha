package com.andharness.ru.data.tools.files

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for [FileMimeDetector] — magic-byte signatures (high confidence), extension fallback
 * (medium) and the weak text/binary guess (low). Pure `java.io.File`, no Android runtime.
 */
class FileMimeDetectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    private fun text(name: String, content: String): File =
        file(name, content.toByteArray(Charsets.UTF_8))

    // ---- magic-byte signatures (HIGH) ------------------------------------------------------

    @Test
    fun pngSignatureIsDetectedHigh() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
        val guess = FileMimeDetector.detect(file("img.bin", png))
        assertEquals("image/png", guess.mime)
        assertEquals(MimeConfidence.HIGH, guess.confidence)
    }

    @Test
    fun jpegSignatureIsDetectedHigh() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(32)
        val guess = FileMimeDetector.detect(file("noext", jpeg))
        assertEquals("image/jpeg", guess.mime)
        assertEquals(MimeConfidence.HIGH, guess.confidence)
    }

    @Test
    fun pdfSignatureIsDetectedHigh() {
        val guess = FileMimeDetector.detect(text("report.dat", "%PDF-1.7\n%%EOF\n"))
        assertEquals("application/pdf", guess.mime)
        assertEquals(MimeConfidence.HIGH, guess.confidence)
    }

    @Test
    fun gzipSignatureIsDetectedHigh() {
        val guess = FileMimeDetector.detect(file("blob", byteArrayOf(0x1F, 0x8B.toByte()) + ByteArray(16)))
        assertEquals("application/gzip", guess.mime)
        assertEquals(MimeConfidence.HIGH, guess.confidence)
    }

    @Test
    fun zipSignatureIsDetectedHigh() {
        val guess = FileMimeDetector.detect(file("blob", byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(16)))
        assertEquals("application/zip", guess.mime)
        assertEquals(MimeConfidence.HIGH, guess.confidence)
    }

    @Test
    fun gifSignatureIsDetectedHigh() {
        val guess = FileMimeDetector.detect(text("pic", "GIF89a" + "x".repeat(20)))
        assertEquals("image/gif", guess.mime)
        assertEquals(MimeConfidence.HIGH, guess.confidence)
    }

    @Test
    fun sevenZipSignatureIsDetectedHigh() {
        val sevenZ = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C) + ByteArray(16)
        val guess = FileMimeDetector.detect(file("a.bin", sevenZ))
        assertEquals("application/x-7z-compressed", guess.mime)
        assertEquals(MimeConfidence.HIGH, guess.confidence)
    }

    @Test
    fun sqliteSignatureIsDetectedHigh() {
        val guess = FileMimeDetector.detect(text("db.bin", "SQLite format 3\u0000" + "x".repeat(10)))
        assertEquals("application/vnd.sqlite3", guess.mime)
        assertEquals(MimeConfidence.HIGH, guess.confidence)
    }

    @Test
    fun riffWebpIsDetectedHigh() {
        val header = "RIFF".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0) +
            "WEBP".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val guess = FileMimeDetector.detect(file("a.bin", header))
        assertEquals("image/webp", guess.mime)
        assertEquals(MimeConfidence.HIGH, guess.confidence)
    }

    @Test
    fun isoBmffFtypIsDetectedAsMp4High() {
        val header = byteArrayOf(0, 0, 0, 0x18) + "ftyp".toByteArray(Charsets.US_ASCII) +
            "isom".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val guess = FileMimeDetector.detect(file("a.bin", header))
        assertEquals("video/mp4", guess.mime)
        assertEquals(MimeConfidence.HIGH, guess.confidence)
    }

    @Test
    fun tiffLittleEndianIsDetectedHigh() {
        val guess = FileMimeDetector.detect(
            file("a.bin", byteArrayOf(0x49, 0x49, 0x2A, 0x00) + ByteArray(16))
        )
        assertEquals("image/tiff", guess.mime)
        assertEquals(MimeConfidence.HIGH, guess.confidence)
    }

    // ---- extension fallback (MEDIUM) -------------------------------------------------------

    @Test
    fun knownExtensionWithoutSignatureIsMedium() {
        val guess = FileMimeDetector.detect(text("photo.heic", "not really heic"))
        assertEquals("image/heic", guess.mime)
        assertEquals(MimeConfidence.MEDIUM, guess.confidence)
        assertEquals("heic", guess.extension)
    }

    @Test
    fun plainTextExtensionIsMedium() {
        val guess = FileMimeDetector.detect(text("notes.txt", "hello world"))
        assertEquals(FileMimeDetector.TEXT_MIME, guess.mime)
        assertEquals(MimeConfidence.MEDIUM, guess.confidence)
    }

    // ---- weak guess (LOW) ------------------------------------------------------------------

    @Test
    fun textWithoutExtensionIsLowPlainText() {
        val guess = FileMimeDetector.detect(text("README", "just some words"))
        assertEquals(FileMimeDetector.TEXT_MIME, guess.mime)
        assertEquals(MimeConfidence.LOW, guess.confidence)
    }

    @Test
    fun binaryWithoutExtensionOrSignatureIsLowUnknown() {
        val guess = FileMimeDetector.detect(file("blob", byteArrayOf(0x00, 0x01, 0x02, 0x03)))
        assertEquals(FileMimeDetector.UNKNOWN_MIME, guess.mime)
        assertEquals(MimeConfidence.LOW, guess.confidence)
    }

    @Test
    fun missingFileIsLowUnknown() {
        val guess = FileMimeDetector.detect(File(temp.root, "does-not-exist"))
        assertEquals(FileMimeDetector.UNKNOWN_MIME, guess.mime)
        assertEquals(MimeConfidence.LOW, guess.confidence)
    }
}
