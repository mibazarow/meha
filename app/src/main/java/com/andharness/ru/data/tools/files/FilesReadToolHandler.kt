package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.RandomAccessFile

/** `files_read`: reads a text file, optionally from an offset and with a byte limit. */
internal class FilesReadToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_read"
    override suspend fun execute(arguments: JsonObject): ToolResult = try {
        val file = File(io.path(arguments, "path"))
        if (!file.isFile) {
            ToolResult(false, "Не файл: «${file.name}».")
        } else {
            val size = file.length()
            // java.io.RandomAccessFile supports a positioned read (seek + read) without
            // loading the whole file, so an oversized file cannot OOM the tool.
            // java.io.RandomAccessFile —
            // https://developer.android.com/reference/java/io/RandomAccessFile
            RandomAccessFile(file, "r").use { raf ->
                // Binary detection: a NUL byte ('\u0000') within the first 8 KiB marks a
                // non-text file, which is reported honestly instead of being garbage-decoded.
                val probeLen = minOf(size, BINARY_PROBE_BYTES.toLong()).toInt()
                val probe = ByteArray(probeLen)
                var probed = 0
                while (probed < probeLen) {
                    val n = raf.read(probe, probed, probeLen - probed)
                    if (n < 0) break
                    probed += n
                }
                if (probe.any { it == 0.toByte() }) {
                    return@use ToolResult(
                        false,
                        "Это бинарный файл; используйте files_hash/files_stat. " +
                            "Показан размер: $size байт."
                    )
                }

                val offset = ((arguments["offset"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
                    .coerceAtLeast(0L)
                // Defaults to MAX_READ_BYTES but may be raised by the caller up to
                // MAX_READ_LIMIT, so a model-supplied limit cannot allocate an unbounded
                // buffer while still allowing reads larger than 1 MiB. The truncation
                // notice below still reports the real file size and offset.
                val limit = ((arguments["limit"] as? JsonPrimitive)?.content?.toLongOrNull()
                    ?: MAX_READ_BYTES.toLong())
                    .coerceAtLeast(1L)
                    .coerceAtMost(MAX_READ_LIMIT)

                raf.seek(offset)
                val buffer = ByteArray(limit.toInt())
                var read = 0
                while (read < buffer.size) {
                    val n = raf.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                // Java's String(byte[], int, int, Charset) always replaces malformed input
                // with U+FFFD instead of throwing, so a partially corrupt file still reads.
                val text = String(buffer, 0, read, Charsets.UTF_8)
                val remaining = size - offset
                val truncated = remaining > read || (size > 0L && read == 0)
                if (truncated) {
                    ToolResult(
                        true,
                        text + "…[прочитано $read из $size байт, смещение $offset]"
                    )
                } else {
                    ToolResult(true, text)
                }
            }
        }
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }

    private companion object {
        // files_read limits: default (and maximum) bytes returned per call, and the probe window
        // used for binary detection.
        private const val MAX_READ_BYTES = 1_048_576
        private const val MAX_READ_LIMIT = 64L * 1024 * 1024
        private const val BINARY_PROBE_BYTES = 8 * 1024
    }
}
