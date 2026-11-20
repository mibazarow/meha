package com.andharness.ru.data.tools.documents

import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Whole-file text reading with byte-order-mark handling, for the documents tooling.
 *
 * References:
 * - `java.io.File` — https://developer.android.com/reference/java/io/File
 */
internal object DocumentTextCodec {

    /** Upper bound for a whole text file read into memory (4 MiB). */
    private const val MAX_TEXT_BYTES = 4 * 1024 * 1024

    /** Reads a whole (bounded) file as text, honouring a UTF-8/UTF-16 LE/BE byte-order mark. */
    fun readFileText(file: File): String? = try {
        FileInputStream(file).use { input -> decodeText(BoundedStreamReader.read(input, MAX_TEXT_BYTES)) }
    } catch (e: IOException) {
        null
    }

    /** Decodes [bytes] as UTF-8, stripping a UTF-8/UTF-16 LE/BE byte-order mark when present. */
    fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }
}
