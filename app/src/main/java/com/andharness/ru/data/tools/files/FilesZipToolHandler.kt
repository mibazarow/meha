package com.andharness.ru.data.tools.files

import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** `files_zip`: archives several validated sources into a new ZIP, verifying the entry count. */
internal class FilesZipToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_zip"

    /**
     * Relative, ZIP-safe entry name: never absolute, never contains `..`.
     * java.util.zip.ZipEntry — https://developer.android.com/reference/java/util/zip/ZipEntry
     */
    private fun entryNameOf(file: File, baseDir: File): String =
        file.relativeToOrSelf(baseDir).path
            .replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val sourcesRaw = (arguments["sources"] as? JsonPrimitive)?.content.orEmpty()
            val names = sourcesRaw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) return ToolResult(false, "Укажите sources — пути через запятую.")
            val base = io.root()
            val dest = File(io.path(arguments, "destination"))

            // Validate EVERY source element through the containment helper (A1). An element
            // that escapes the root (absolute, `..`, NUL, prefix confusion) is an error and
            // is NEVER silently used or skipped.
            val invalid = mutableListOf<String>()
            val validated = mutableListOf<File>()
            for (name in names) {
                try {
                    validated += resolveSafePath(io.roots(), name)
                } catch (e: IllegalArgumentException) {
                    invalid += "«$name» (${e.message})"
                }
            }
            if (invalid.isNotEmpty()) {
                return ToolResult(
                    false,
                    "Некорректные источники: ${invalid.joinToString("; ")}. Архив не создан."
                )
            }

            // A validated source that does not exist on disk is reported as skipped,
            // never silently dropped.
            val skipped = mutableListOf<String>()
            val sources = validated.filter { src ->
                if (src.exists()) true else { skipped += "«${src.name}»"; false }
            }
            if (sources.isEmpty()) {
                return ToolResult(
                    false,
                    "Нечего архивировать: ни один источник не найден." +
                        if (skipped.isNotEmpty()) " Пропущены (не найдены): ${skipped.joinToString(", ")}." else ""
                )
            }

            dest.parentFile?.mkdirs()
            var entries = 0
            // java.util.zip.ZipOutputStream —
            // https://developer.android.com/reference/java/util/zip/ZipOutputStream
            ZipOutputStream(BufferedOutputStream(dest.outputStream())).use { zos ->
                for (src in sources) {
                    if (src.isDirectory) {
                        val baseDir = src.parentFile ?: base
                        src.walkTopDown().filter { it.isFile }.forEach { f ->
                            zos.putNextEntry(ZipEntry(entryNameOf(f, baseDir)))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                            entries++
                        }
                    } else {
                        zos.putNextEntry(ZipEntry(entryNameOf(src, src.parentFile ?: base)))
                        src.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        entries++
                    }
                }
            }

            // Nothing written → honest failure, and no empty archive left behind.
            if (entries == 0) {
                dest.delete()
                return ToolResult(false, "Нечего архивировать: ни один источник не найден.")
            }

            // Post-verification: the destination must exist, be non-empty and be a valid
            // ZIP containing exactly the entries we wrote.
            // java.util.zip.ZipFile — https://developer.android.com/reference/java/util/zip/ZipFile
            if (!dest.exists() || dest.length() <= 0L) {
                dest.delete()
                return ToolResult(false, "Архив не создан: файл пуст или отсутствует (${dest.name}).")
            }
            val actual = try {
                ZipFile(dest).use { it.size() }
            } catch (e: Exception) {
                dest.delete()
                return ToolResult(false, "Архив повреждён: ${e.message}. Файл удалён.")
            }
            if (actual != entries) {
                dest.delete()
                return ToolResult(
                    false,
                    "Архив повреждён: записано $entries, в архиве $actual. Файл удалён."
                )
            }

            val skippedNote =
                if (skipped.isNotEmpty()) " Пропущены (не найдены): ${skipped.joinToString(", ")}." else ""
            ToolResult(true, "Создан архив (${dest.name}, $entries файлов): ${dest.path}.$skippedNote")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
