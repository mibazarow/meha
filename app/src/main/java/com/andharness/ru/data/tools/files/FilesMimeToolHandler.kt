package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `files_mime`: reports a file's MIME type with a confidence level and the extension, detected
 * from the magic bytes first and the extension second ([FileMimeDetector]). The evidence for the
 * verdict is included so the result is verifiable rather than a bare guess.
 *
 * References:
 * - `java.io.File` — https://developer.android.com/reference/java/io/File
 * - MIME type naming (RFC 6838) via the media-storage guide —
 *   https://developer.android.com/training/data-storage/shared/media
 */
internal class FilesMimeToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_mime"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val file = File(io.path(arguments, "path"))
            if (!file.exists()) return ToolResult(false, "Не найдено: ${file.absolutePath}")
            if (file.isDirectory) return ToolResult(false, "«${file.name}» — директория, а не файл.")
            if (!file.isFile) return ToolResult(false, "Не файл: «${file.absolutePath}».")

            val guess = FileMimeDetector.detect(file)
            val extension = guess.extension.ifEmpty { "(нет)" }
            ToolResult(
                true,
                "«${file.name}»: тип ${guess.mime}; уверенность ${guess.confidence.labelRu}; " +
                    "расширение: .$extension; основание: ${guess.evidence}."
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
