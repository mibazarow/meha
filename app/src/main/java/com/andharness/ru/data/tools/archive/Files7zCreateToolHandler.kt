package com.andharness.ru.data.tools.archive

import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File

/**
 * `files_7z_create`: builds a 7z archive from the given sources and post-verifies it by reopening
 * the result and counting its entries.
 *
 * Apache Commons Compress references (artifactId org.apache.commons:commons-compress):
 * - SevenZOutputFile (7z writing) —
 *   https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/sevenz/SevenZOutputFile.html
 *   (putArchiveEntry(SevenZArchiveEntry) / write(byte[], int, int) / closeArchiveEntry() / finish();
 *   putArchiveEntry(SevenZArchiveEntry) is @since 1.25.0, so it is available in 1.27.1)
 * - SevenZArchiveEntry —
 *   https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/sevenz/SevenZArchiveEntry.html
 *   (setName / setSize / setHasStream — an entry without a stream is treated as a directory/empty)
 */
internal class Files7zCreateToolHandler(
    private val support: ArchiveSupport,
) : ToolHandler {

    override val name = "files_7z_create"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val names = (arguments["sources"] as? JsonPrimitive)?.content.orEmpty()
                .split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) {
                return ToolResult(false, "Укажите sources — пути через запятую.")
            }
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
                SevenZOutputFile(destination).use { sevenZ ->
                    for (src in sources) {
                        val files = if (src.isDirectory) {
                            val baseDir = src.parentFile ?: support.root()
                            src.walkTopDown().filter { it.isFile }
                                .map { it to support.entryNameOf(it, baseDir) }.toList()
                        } else {
                            listOf(
                                src to support.entryNameOf(src, src.parentFile ?: support.root())
                            )
                        }
                        for ((file, name) in files) {
                            val entry = SevenZArchiveEntry()
                            entry.setName(name)
                            entry.setSize(file.length())
                            // Without a stream flag the entry is read back as a directory.
                            entry.setHasStream(true)
                            sevenZ.putArchiveEntry(entry)
                            file.inputStream().use { ins ->
                                val buffer = ByteArray(ArchiveLimits.COPY_BUFFER_SIZE)
                                while (true) {
                                    val read = ins.read(buffer)
                                    if (read < 0) break
                                    sevenZ.write(buffer, 0, read)
                                }
                            }
                            sevenZ.closeArchiveEntry()
                            entries++
                        }
                    }
                    sevenZ.finish()
                }
            } catch (e: Exception) {
                // A failed write must not leave a partial archive behind.
                destination.delete()
                return ToolResult(false, "Не удалось создать 7z-архив: ${e.message}. Файл удалён.")
            } catch (e: LinkageError) {
                // SevenZOutputFile needs the optional org.tukaani:xz codec; when that class
                // is missing the JVM throws NoClassDefFoundError (an Error). Delete the
                // already-created partial file so nothing leaks.
                destination.delete()
                return ToolResult(
                    false,
                    "Не удалось создать 7z-архив: отсутствует зависимость среды выполнения " +
                        "(${e.message ?: e::class.simpleName}). Файл удалён."
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

            // Post-verification: reopen the written archive and count the entries;
            // a mismatch means the archive is corrupt and is deleted.
            val actual = try {
                support.countSevenZEntries(destination)
            } catch (e: Exception) {
                destination.delete()
                return ToolResult(false, "Архив повреждён: ${e.message}. Файл удалён.")
            } catch (e: LinkageError) {
                destination.delete()
                return ToolResult(
                    false,
                    "Архив не проверен: отсутствует зависимость среды выполнения " +
                        "(${e.message ?: e::class.simpleName}). Файл удалён."
                )
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
                "Создан 7z-архив (${destination.name}, $entries файлов): " +
                    "${destination.absolutePath}.$skippedNote"
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
