package com.andharness.ru.data.tools.files

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale

/** How certain a [MimeGuess] is: a magic-byte match, an extension-only match, or a weak fallback. */
internal enum class MimeConfidence(val label: String, val labelRu: String) {
    /** The file's leading bytes matched a known signature. */
    HIGH("high", "высокая"),

    /** No signature matched, but the extension maps to a known MIME type. */
    MEDIUM("medium", "средняя"),

    /** Neither signature nor extension was conclusive (text or unknown binary). */
    LOW("low", "низкая"),
}

/** One MIME detection result: the type, how sure we are, the extension, and the evidence used. */
internal data class MimeGuess(
    val mime: String,
    val confidence: MimeConfidence,
    val extension: String,
    val evidence: String,
)

/**
 * Detects a file's MIME type from its magic bytes first and its extension second — the same
 * two-step approach as [com.andharness.ru.data.tools.documents.DocumentFormatDetector], but
 * self-contained and multi-format (pure `java.io.File` / `java.io.FileInputStream`, no Android
 * APIs) so it is unit-testable on the JVM. Only the first [HEADER_BYTES] bytes are read, so an
 * arbitrarily large file is never loaded into memory.
 *
 * References:
 * - `java.io.File` — https://developer.android.com/reference/java/io/File
 * - `java.io.FileInputStream` — https://developer.android.com/reference/java/io/FileInputStream
 * - Registered media MIME types (RFC 6838 naming) —
 *   https://developer.android.com/training/data-storage/shared/media
 */
internal object FileMimeDetector {

    /** Leading bytes inspected for a signature (covers every signature below). */
    private const val HEADER_BYTES = 1024

    /** Fallback for data that is neither a known format nor recognizably text. */
    const val UNKNOWN_MIME = "application/octet-stream"

    /** Fallback for content that has no NUL byte in its header. */
    const val TEXT_MIME = "text/plain"

    private val PNG =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val GIF87 = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89 = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val PDF = "%PDF".toByteArray(Charsets.US_ASCII)
    private val ZIP_LOCAL = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val ZIP_SPANNED = byteArrayOf(0x50, 0x4B, 0x07, 0x08)
    private val GZIP = byteArrayOf(0x1F, 0x8B.toByte())
    private val RAR = "Rar!\u001A\u0007".toByteArray(Charsets.ISO_8859_1)
    private val SEVEN_Z = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val OLE2 = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )
    private val ELF = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
    private val BMP = byteArrayOf(0x42, 0x4D)
    private val TIFF_LE = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
    private val TIFF_BE = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)
    private val FLAC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val OGG = "OggS".toByteArray(Charsets.US_ASCII)
    private val ID3 = "ID3".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
    private val SQLITE = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** Extension → MIME for formats that are reliable (or only) identified by their extension. */
    private val BY_EXTENSION: Map<String, String> = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "tif" to "image/tiff", "tiff" to "image/tiff", "heic" to "image/heic",
        "svg" to "image/svg+xml",
        "pdf" to "application/pdf",
        "txt" to TEXT_MIME, "log" to TEXT_MIME, "md" to "text/markdown",
        "markdown" to "text/markdown", "csv" to "text/csv", "tsv" to "text/tab-separated-values",
        "json" to "application/json", "xml" to "application/xml",
        "html" to "text/html", "htm" to "text/html",
        "yaml" to "application/yaml", "yml" to "application/yaml",
        "js" to "text/javascript", "kt" to "text/x-kotlin", "java" to "text/x-java-source",
        "py" to "text/x-python", "sh" to "application/x-sh", "gradle" to "text/plain",
        "properties" to "text/plain", "ini" to "text/plain", "conf" to "text/plain",
        "zip" to "application/zip", "gz" to "application/gzip", "tgz" to "application/gzip",
        "tar" to "application/x-tar", "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav",
        "ogg" to "audio/ogg", "opus" to "audio/opus", "flac" to "audio/flac", "amr" to "audio/amr",
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mov" to "video/quicktime",
        "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp",
        "apk" to "application/vnd.android.package-archive",
        "db" to "application/vnd.sqlite3", "sqlite" to "application/vnd.sqlite3",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "rtf" to "application/rtf",
    )

    /** Sniffs [file]: magic bytes first, then extension, then a weak text/binary guess. */
    fun detect(file: File): MimeGuess {
        val extension = file.extension.lowercase(Locale.ROOT)
        val header = readHeader(file)

        magicMime(header)?.let { (mime, evidence) ->
            return MimeGuess(mime, MimeConfidence.HIGH, extension, evidence)
        }

        BY_EXTENSION[extension]?.let { mime ->
            return MimeGuess(mime, MimeConfidence.MEDIUM, extension, "extension .$extension")
        }

        if (header.isEmpty()) {
            return MimeGuess(UNKNOWN_MIME, MimeConfidence.LOW, extension, "no data")
        }
        if (header.any { it == 0.toByte() }) {
            return MimeGuess(UNKNOWN_MIME, MimeConfidence.LOW, extension, "binary content")
        }
        return MimeGuess(TEXT_MIME, MimeConfidence.LOW, extension, "text content")
    }

    /** Returns the MIME and evidence when a signature matches the [header], otherwise `null`. */
    private fun magicMime(header: ByteArray): Pair<String, String>? {
        return when {
            header.startsWith(PNG) -> "image/png" to "signature PNG"
            header.startsWith(JPEG) -> "image/jpeg" to "signature JPEG"
            header.startsWith(GIF87) -> "image/gif" to "signature GIF87a"
            header.startsWith(GIF89) -> "image/gif" to "signature GIF89a"
            header.startsWith(BMP) -> "image/bmp" to "signature BMP"
            header.startsWith(TIFF_LE) || header.startsWith(TIFF_BE) ->
                "image/tiff" to "signature TIFF"
            header.startsWith(PDF) -> "application/pdf" to "signature %PDF"
            header.startsWith(ZIP_LOCAL) || header.startsWith(ZIP_EMPTY) ||
                header.startsWith(ZIP_SPANNED) -> "application/zip" to "signature ZIP"
            header.startsWith(GZIP) -> "application/gzip" to "signature GZIP"
            header.startsWith(RAR) -> "application/vnd.rar" to "signature RAR"
            header.startsWith(SEVEN_Z) -> "application/x-7z-compressed" to "signature 7z"
            header.startsWith(OLE2) -> "application/x-ole-storage" to "signature OLE2"
            header.startsWith(ELF) -> "application/x-executable" to "signature ELF"
            header.startsWith(SQLITE) -> "application/vnd.sqlite3" to "signature SQLite"
            header.startsWith(FLAC) -> "audio/flac" to "signature FLAC"
            header.startsWith(OGG) -> "audio/ogg" to "signature OggS"
            header.startsWith(ID3) -> "audio/mpeg" to "signature ID3"
            header.riffMime() != null -> header.riffMime()!! to "signature RIFF"
            header.ftypMime() != null -> header.ftypMime()!! to "signature ISO-BMFF/MP4"
            else -> null
        }
    }

    /** RIFF container subtype at bytes 8..11: WEBP / WAVE / AVI. */
    private fun ByteArray.riffMime(): String? {
        if (!startsWith(RIFF) || size < 12) return null
        val form = String(this, 8, 4, Charsets.US_ASCII)
        return when (form) {
            "WEBP" -> "image/webp"
            "WAVE" -> "audio/wav"
            "AVI " -> "video/x-msvideo"
            else -> null
        }
    }

    /** ISO Base Media File Format (`ftyp` at offset 4): reported as MP4. */
    private fun ByteArray.ftypMime(): String? =
        if (size >= 12 && regionMatches(4, FTYP)) "video/mp4" else null

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && regionMatches(0, prefix)

    /** Byte-wise comparison of [prefix] against this array starting at [offset]. */
    private fun ByteArray.regionMatches(offset: Int, prefix: ByteArray): Boolean {
        if (offset + prefix.size > size) return false
        for (index in prefix.indices) {
            if (this[offset + index] != prefix[index]) return false
        }
        return true
    }

    /** Reads up to [HEADER_BYTES] leading bytes; returns an empty array when unreadable. */
    private fun readHeader(file: File): ByteArray {
        if (!file.isFile) return ByteArray(0)
        return try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(HEADER_BYTES)
                var offset = 0
                while (offset < HEADER_BYTES) {
                    val read = input.read(buffer, offset, HEADER_BYTES - offset)
                    if (read < 0) break
                    offset += read
                }
                if (offset == HEADER_BYTES) buffer else buffer.copyOf(offset)
            }
        } catch (e: IOException) {
            ByteArray(0)
        }
    }
}
