package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** `files_zip_list`: lists the entries of a ZIP archive without unpacking them. */
internal class FilesZipListToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_zip_list"
    override suspend fun execute(arguments: JsonObject): ToolResult = try {
        val pathArg = io.path(arguments, "path")
        val file = File(pathArg)
        if (!file.exists()) {
            ToolResult(false, "Не найдено: «$pathArg».")
        } else if (!file.isFile) {
            ToolResult(false, "Не файл: «$pathArg».")
        } else {
            // Bounded output: default 200 lines, never more than 5000, never less than 1.
            val limit = ((arguments["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: DEFAULT_ZIP_LIST_LIMIT)
                .coerceAtLeast(1)
                .coerceAtMost(MAX_ZIP_LIST_LIMIT)
            // java.util.zip.ZipFile — reads the archive's central directory; iterating
            // entries() gives the real total count without unpacking any entry's data.
            // https://developer.android.com/reference/java/util/zip/ZipFile
            ZipFile(file).use { zip ->
                val lines = ArrayList<String>()
                var total = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    total++
                    if (lines.size < limit) {
                        val name = if (entry.isDirectory && !entry.name.endsWith("/")) {
                            "${entry.name}/"
                        } else {
                            entry.name
                        }
                        lines.add("$name  ${entry.size} → ${entry.compressedSize} байт")
                    }
                }
                val truncated = total > lines.size
                val summary = "Всего записей: $total, показано: ${lines.size}. " +
                    "Усечено: ${if (truncated) "да" else "нет"}."
                val body = if (lines.isEmpty()) "Архив пуст." else lines.joinToString("\n")
                ToolResult(true, "$summary\n$body")
            }
        }
    } catch (e: ZipException) {
        ToolResult(false, "Не является корректным ZIP-архивом: ${e.message}.")
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }

    private companion object {
        // files_zip_list output bound: default and maximum number of entries printed per call.
        private const val DEFAULT_ZIP_LIST_LIMIT = 200
        private const val MAX_ZIP_LIST_LIMIT = 5000
    }
}
