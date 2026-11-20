package com.andharness.ru.data.tools.documents

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded stream reads (manual loop — `InputStream.readNBytes` is not available on minSdk 26). */
internal object BoundedStreamReader {

    private const val READ_CHUNK = 8 * 1024

    /** Reads at most [limit] bytes from [input]. */
    fun read(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        var total = 0
        while (total < limit) {
            val wanted = minOf(buffer.size, limit - total)
            val read = input.read(buffer, 0, wanted)
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }
}
