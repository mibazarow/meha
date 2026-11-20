package com.andharness.ru.data.tools.files

import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `files_join`: concatenates ordered `«name».partNN` parts back into one file. Parts can be given
 * explicitly (comma/semicolon-separated `parts`) or discovered automatically from any one part via
 * `source` (siblings of the same base name are collected and naturally ordered by part index).
 * Every part must exist and be a regular file; an optional `expected_size` is checked against the
 * sum of the parts BEFORE writing, so a mismatch aborts without producing a partial file.
 *
 * References:
 * - `java.io.File` — https://developer.android.com/reference/java/io/File
 * - `java.io.FileInputStream` / `java.io.FileOutputStream` —
 *   https://developer.android.com/reference/java/io/FileInputStream
 */
internal class FilesJoinToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_join"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val destination = File(io.path(arguments, "destination"))
            val partsRaw = arguments.str("parts")
            val sourceRaw = arguments.str("source")

            val parts: List<File>
            when {
                partsRaw != null -> {
                    val names = partsRaw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                    if (names.isEmpty()) return ToolResult(false, "parts пуст — укажите пути частей через запятую.")
                    val resolved = ArrayList<File>()
                    val invalid = ArrayList<String>()
                    for (name in names) {
                        try {
                            resolved += File(io.resolvePath(name))
                        } catch (e: IllegalArgumentException) {
                            invalid += "«$name» (${e.message})"
                        }
                    }
                    if (invalid.isNotEmpty()) return ToolResult(false, "Некорректные пути частей: ${invalid.joinToString("; ")}.")
                    val missing = resolved.filterNot { it.isFile }
                    if (missing.isNotEmpty()) {
                        return ToolResult(false, "Не найдены части: ${missing.joinToString(", ") { it.name }}.")
                    }
                    parts = resolved
                }
                sourceRaw != null -> {
                    val sample = File(io.resolvePath(sourceRaw))
                    if (!sample.isFile) return ToolResult(false, "Не файл: «${sample.absolutePath}».")
                    val base = FileSplitSupport.baseNameOfPart(sample.name)
                        ?: return ToolResult(false, "Имя «${sample.name}» не похоже на часть (ожидается «имя.partNN»). Укажите parts явно.")
                    val dir = sample.parentFile ?: return ToolResult(false, "Не удалось определить папку части.")
                    val siblings = dir.listFiles().orEmpty()
                        .filter { it.isFile && FileSplitSupport.baseNameOfPart(it.name) == base }
                    if (siblings.isEmpty()) return ToolResult(false, "Части «$base.partNN» не найдены в «${dir.absolutePath}».")
                    parts = FileSplitSupport.orderParts(siblings.map { it.name }).map { File(dir, it) }
                }
                else -> return ToolResult(false, "Укажите parts (пути частей) или source (любую часть для авто-поиска).")
            }
            if (parts.isEmpty()) return ToolResult(false, "Не найдено ни одной части для объединения.")

            val total = parts.sumOf { it.length() }
            val expectedRaw = arguments.str("expected_size")
            if (expectedRaw != null) {
                val expected = expectedRaw.toLongOrNull()
                    ?: FileSplitSupport.parseByteCount(expectedRaw)
                    ?: return ToolResult(false, "expected_size: некорректное значение «$expectedRaw».")
                if (expected != total) {
                    return ToolResult(
                        false,
                        "Размер частей ($total байт) не совпал с ожидаемым ($expected байт). Объединение отменено."
                    )
                }
            }

            if (destination.exists()) {
                return ToolResult(false, "Назначение уже существует: «${destination.absolutePath}». Объединение отменено.")
            }
            destination.parentFile?.mkdirs()

            try {
                destination.outputStream().buffered(COPY_BUFFER_SIZE).use { output ->
                    for (part in parts) {
                        part.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
                            input.copyTo(output, COPY_BUFFER_SIZE)
                        }
                    }
                }
            } catch (e: Exception) {
                destination.delete()
                return ToolResult(false, "Не удалось объединить части: ${e.message}. Назначение удалено.")
            }

            val written = destination.length()
            if (!destination.isFile || written != total) {
                destination.delete()
                return ToolResult(false, "Объединение не подтверждено: записано $written из $total байт. Назначение удалено.")
            }

            val listing = parts.joinToString("\n") { "• ${it.name} — ${it.length()} байт" }
            ToolResult(
                true,
                "Объединено ${parts.size} частей → «${destination.absolutePath}» " +
                    "(${formatBytes(written)}, $written байт):\n$listing"
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    private companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
