package com.andharness.ru.data.tools.archive

import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * `files_tar_create`: builds a TAR (optionally gzip-compressed) archive from the given sources and
 * post-verifies it by reopening the result and counting its entries.
 *
 * Apache Commons Compress references (artifactId org.apache.commons:commons-compress):
 * - TarArchiveOutputStream —
 *   https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/tar/TarArchiveOutputStream.html
 *   (putArchiveEntry / write / closeArchiveEntry / setLongFileMode(LONGFILE_POSIX))
 * - TarArchiveEntry —
 *   https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/tar/TarArchiveEntry.html
 *   (TarArchiveEntry(File, String) copies size/mode/mtime from the file)
 * - GzipCompressorOutputStream —
 *   https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/compressors/gzip/GzipCompressorOutputStream.html
 */
internal class FilesTarCreateToolHandler(
    private val support: ArchiveSupport,
) : ToolHandler {

    override val name = "files_tar_create"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val names = (arguments["sources"] as? JsonPrimitive)?.content.orEmpty()
                .split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) {
                return ToolResult(false, "Укажите sources — пути через запятую.")
            }
            val useGzip = (arguments["gzip"] as? JsonPrimitive)?.content
                ?.toBooleanStrictOrNull() ?: false
            val destination = File(support.path(arguments, "destination"))

            // Validate EVERY source element through resolveSafePath. An element that
            // escapes the root (absolute outside it, `..`, NUL) is an error and is NEVER
            // silently used or skipped.
            val invalid = mutableListOf<String>()
            val validated = mutableListOf<File>()
            for (name in names) {
                try {
                    validated += resolveSafePath(support.root(), name)
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
                        if (skipped.isNotEmpty()) {
                            " Пропущены (не найдены): ${skipped.joinToString(", ")}."
                        } else ""
                )
            }

            // No silent overwrite: an existing destination is an honest error.
            if (destination.exists()) {
                return ToolResult(
                    false,
                    "Назначение уже существует: «${destination.absolutePath}». Архив не создан."
                )
            }

            destination.parentFile?.mkdirs()
            var entries = 0
            try {
                val fileOut = FileOutputStream(destination)
                val sink = if (useGzip) {
                    GzipCompressorOutputStream(BufferedOutputStream(fileOut))
                } else {
                    BufferedOutputStream(fileOut)
                }
                TarArchiveOutputStream(sink).use { tar ->
                    // POSIX/PAX long-name extension: entry names >= 100 chars are stored
                    // losslessly instead of failing with LONGFILE_ERROR (the default).
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    for (src in sources) {
                        if (src.isDirectory) {
                            val baseDir = src.parentFile ?: support.root()
                            src.walkTopDown().filter { it.isFile }.forEach { f ->
                                tar.putArchiveEntry(
                                    TarArchiveEntry(f, support.entryNameOf(f, baseDir))
                                )
                                f.inputStream().use { it.copyTo(tar) }
                                tar.closeArchiveEntry()
                                entries++
                            }
                        } else {
                            tar.putArchiveEntry(
                                TarArchiveEntry(
                                    src,
                                    support.entryNameOf(src, src.parentFile ?: support.root())
                                )
                            )
                            src.inputStream().use { it.copyTo(tar) }
                            tar.closeArchiveEntry()
                            entries++
                        }
                    }
                }
            } catch (e: Exception) {
                // A failed write must not leave a partial archive behind.
                destination.delete()
                return ToolResult(false, "Не удалось создать TAR-архив: ${e.message}. Файл удалён.")
            } catch (e: LinkageError) {
                // e.g. NoClassDefFoundError for an optional codec: still must not leak a
                // partial file. LinkageError is a recoverable Error (missing class), unlike
                // OOM which is allowed to propagate.
                destination.delete()
                return ToolResult(
                    false,
                    "Не удалось создать TAR-архив: ${e.message ?: e::class.simpleName}. Файл удалён."
                )
            }

            // Nothing written → honest failure, and no empty archive left behind.
            if (entries == 0) {
                destination.delete()
                return ToolResult(false, "Нечего архивировать: ни один источник не найден.")
            }
            if (!destination.exists() || destination.length() <= 0L) {
                destination.delete()
                return ToolResult(
                    false,
                    "Архив не создан: файл пуст или отсутствует (${destination.name})."
                )
            }

            // Post-verification: reopen the written archive (gzip-aware) and count the
            // entries; a mismatch means the archive is corrupt and is deleted.
            val actual = try {
                support.countTarEntries(destination)
            } catch (e: Exception) {
                destination.delete()
                return ToolResult(false, "Архив повреждён: ${e.message}. Файл удалён.")
            }
            if (actual != entries) {
                destination.delete()
                return ToolResult(
                    false,
                    "Архив повреждён: записано $entries, в архиве $actual. Файл удалён."
                )
            }

            val skippedNote = if (skipped.isNotEmpty()) {
                " Пропущены (не найдены): ${skipped.joinToString(", ")}."
            } else ""
            return ToolResult(
                true,
                "Создан TAR-архив${if (useGzip) " (GZIP)" else ""} " +
                    "(${destination.name}, $entries файлов): ${destination.absolutePath}.$skippedNote"
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
