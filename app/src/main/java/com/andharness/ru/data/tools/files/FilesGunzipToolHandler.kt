package com.andharness.ru.data.tools.files

import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/** `files_gunzip`: decompresses a gzip file into a new file. */
internal class FilesGunzipToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_gunzip"
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
                    "Назначение уже существует: «${destination.absolutePath}». Распаковка отменена."
                )
            }

            destination.parentFile?.mkdirs()
            val sourceSize = source.length()
            // java.util.zip.GZIPInputStream decompresses a gzip (RFC 1952) stream; a corrupt
            // or non-gzip input surfaces as a java.util.zip.ZipException while reading.
            // The output is written through a fixed 8 KiB buffer.
            // https://developer.android.com/reference/java/util/zip/GZIPInputStream
            try {
                GZIPInputStream(source.inputStream()).use { gz ->
                    FileOutputStream(destination).use { out ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val read = gz.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: ZipException) {
                // A corrupt / non-gzip input must not leave a partial output behind.
                destination.delete()
                return ToolResult(
                    false,
                    "Файл «${source.absolutePath}» не является корректным GZIP: ${e.message}. " +
                        "Частичный вывод удалён."
                )
            } catch (e: Exception) {
                destination.delete()
                return ToolResult(
                    false,
                    "Не удалось распаковать GZIP: ${e.message}. Частичный вывод удалён."
                )
            }

            // Post-verification: the destination must exist and be non-empty.
            if (!destination.exists() || destination.length() <= 0L) {
                destination.delete()
                return ToolResult(
                    false,
                    "Распаковка не подтверждена: файл пуст или отсутствует (${destination.name}). Файл удалён."
                )
            }

            val destinationSize = destination.length()
            ToolResult(
                true,
                "Распаковано: «${source.absolutePath}» (${formatBytes(sourceSize)}, $sourceSize байт) → " +
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
