package com.andharness.ru.data.tools.files

import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/** `files_gzip`: compresses a file into a new gzip stream. */
internal class FilesGzipToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_gzip"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            // Both ends go through path(), which resolves them against every allowed root
            // and rejects `..` and any canonical result escaping those roots (A1).
            val source = File(io.path(arguments, "source"))
            val destination = File(io.path(arguments, "destination"))

            if (!source.isFile) {
                return ToolResult(false, "Не файл: «${source.absolutePath}».")
            }
            // No silent overwrite: an existing destination is an honest error.
            if (destination.exists()) {
                return ToolResult(
                    false,
                    "Назначение уже существует: «${destination.absolutePath}». Сжатие отменено."
                )
            }

            destination.parentFile?.mkdirs()
            val sourceSize = source.length()
            // java.util.zip.GZIPOutputStream writes a gzip (RFC 1952) stream over the given
            // output stream; the raw bytes are copied with a fixed 8 KiB buffer, so an
            // arbitrarily large file is never loaded into memory.
            // https://developer.android.com/reference/java/util/zip/GZIPOutputStream
            // java.io.FileOutputStream —
            // https://developer.android.com/reference/java/io/FileOutputStream
            try {
                GZIPOutputStream(FileOutputStream(destination)).use { gz ->
                    source.inputStream().use { ins ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val read = ins.read(buffer)
                            if (read < 0) break
                            gz.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: Exception) {
                // A failed compression must not leave a partial file behind.
                destination.delete()
                return ToolResult(false, "Не удалось сжать файл: ${e.message}")
            }

            // Post-verification: the destination must exist and be non-empty.
            if (!destination.exists() || destination.length() <= 0L) {
                destination.delete()
                return ToolResult(
                    false,
                    "Сжатие не подтверждено: файл пуст или отсутствует (${destination.name}). Файл удалён."
                )
            }

            val destinationSize = destination.length()
            ToolResult(
                true,
                "Сжато: «${source.absolutePath}» (${formatBytes(sourceSize)}, $sourceSize байт) → " +
                    "«${destination.absolutePath}» (${formatBytes(destinationSize)}, $destinationSize байт)."
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    private companion object {
        private const val COPY_BUFFER_SIZE = 8 * 1024
    }
}
