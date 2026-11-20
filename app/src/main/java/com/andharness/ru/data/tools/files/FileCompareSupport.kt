package com.andharness.ru.data.tools.files

import java.io.File
import kotlin.math.min

/**
 * Pure, Android-free helpers behind `files_compare`: byte-level first-difference detection (both
 * in-memory and streaming, so a large file is compared with constant memory) and a small,
 * bounded, unified-diff-like summary for text files.
 *
 * References:
 * - `java.io.FileInputStream` — https://developer.android.com/reference/java/io/FileInputStream
 * - `java.io.BufferedInputStream` — https://developer.android.com/reference/java/io/BufferedInputStream
 */
internal object FileCompareSupport {

    /** Buffer used by the streaming comparison (never loads a whole file). */
    private const val BUFFER_SIZE = 64 * 1024

    /** Default cap on how many lines the text summary may contain. */
    const val DEFAULT_MAX_DIFF_LINES = 40

    /**
     * Offset of the first byte where [a] and [b] differ, or `null` when they are byte-identical.
     * When one array is a prefix of the other, returns the length of the shorter array.
     */
    fun firstDifference(a: ByteArray, b: ByteArray): Long? {
        val common = min(a.size, b.size)
        for (index in 0 until common) {
            if (a[index] != b[index]) return index.toLong()
        }
        return if (a.size == b.size) null else common.toLong()
    }

    /**
     * Streaming variant of [firstDifference] over two files with a fixed-size buffer: `null` when
     * byte-identical, otherwise the offset of the first differing byte (the shorter file's length
     * when one is a prefix of the other). Never loads a whole file into memory.
     */
    fun firstDifference(a: File, b: File): Long? {
        a.inputStream().buffered(BUFFER_SIZE).use { inA ->
            b.inputStream().buffered(BUFFER_SIZE).use { inB ->
                val bufA = ByteArray(BUFFER_SIZE)
                val bufB = ByteArray(BUFFER_SIZE)
                var offset = 0L
                while (true) {
                    val readA = fill(inA, bufA)
                    val readB = fill(inB, bufB)
                    val common = min(readA, readB)
                    for (index in 0 until common) {
                        if (bufA[index] != bufB[index]) return offset + index
                    }
                    if (readA != readB) return offset + common
                    if (readA == 0) return null
                    offset += readA
                }
            }
        }
    }

    /** Index of the first line that differs, or `null` when the two line lists are identical. */
    fun firstDifferingLine(a: List<String>, b: List<String>): Int? {
        val common = min(a.size, b.size)
        for (index in 0 until common) {
            if (a[index] != b[index]) return index
        }
        return if (a.size == b.size) null else common
    }

    /**
     * Bounded, unified-diff-like summary centered on the first differing line: equal lines are
     * prefixed with two spaces, an old-only line with `-`, a new-only line with `+`. At most
     * [maxLines] lines are produced (then a `…` marker), so the output cannot blow up on a large
     * file. Returns an empty string when the two lists are identical or [maxLines] is not positive.
     */
    fun unifiedSummary(
        a: List<String>,
        b: List<String>,
        maxLines: Int = DEFAULT_MAX_DIFF_LINES,
        context: Int = 2,
    ): String {
        if (maxLines <= 0) return ""
        val first = firstDifferingLine(a, b) ?: return ""
        val start = maxOf(0, first - context)

        val out = StringBuilder()
        var emitted = 0
        fun emit(line: String): Boolean {
            if (emitted >= maxLines) return false
            if (emitted > 0) out.append('\n')
            out.append(line)
            emitted++
            return true
        }

        for (index in start until first) emit("  ${a[index]}")

        var index = first
        while (emitted < maxLines && (index < a.size || index < b.size)) {
            val av = a.getOrNull(index)
            val bv = b.getOrNull(index)
            if (av == bv) {
                emit("  ${av ?: ""}")
            } else {
                if (av != null && !emit("- $av")) break
                if (bv != null && !emit("+ $bv")) break
            }
            index++
        }

        if (emitted >= maxLines && (index < a.size || index < b.size)) {
            out.append("\n… (сокращено)")
        }
        return out.toString()
    }

    /**
     * Reads up to [buffer].size bytes into [buffer], retrying short reads until the buffer is full
     * or the stream ends. Returns the number of bytes read (0 at end of stream).
     */
    private fun fill(stream: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = stream.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
            if (read == 0) break
        }
        return offset
    }
}
