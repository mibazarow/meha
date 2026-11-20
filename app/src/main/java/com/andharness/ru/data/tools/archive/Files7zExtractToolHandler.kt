package com.andharness.ru.data.tools.archive

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File

/**
 * `files_7z_extract`: extracts a 7z archive into a destination directory, guarding against
 * zip/tar-slip, oversized "archive bombs", and rolling back exactly the files/dirs it created on
 * failure.
 *
 * Apache Commons Compress reference (artifactId org.apache.commons:commons-compress):
 * - SevenZFile (7z reading) —
 *   https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/sevenz/SevenZFile.html
 *   (SevenZFile(File); getNextEntry() advances to the next entry; getInputStream(entry) returns the
 *   entry's decompressed content and must be called on the entry last returned by getNextEntry())
 */
internal class Files7zExtractToolHandler(
    private val support: ArchiveSupport,
) : ToolHandler {

    override val name = "files_7z_extract"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        // Files/dirs this call actually created, so a breach or I/O error can roll back
        // only our own output and never a pre-existing tree.
        val createdFiles = mutableListOf<File>()
        val createdDirs = mutableListOf<File>()
        return try {
            val source = File(support.path(arguments, "source"))
            if (!source.isFile) return ToolResult(false, "Не файл: ${source.name}.")
            val destDir = File(support.path(arguments, "destination"))
            destDir.mkdirs()
            if (!destDir.isDirectory) {
                return ToolResult(
                    false,
                    "Не удалось создать каталог назначения: ${destDir.absolutePath}."
                )
            }
            val destCanon = destDir.canonicalPath
            val destPrefix = destCanon + File.separator

            var count = 0
            var skipped = 0
            var processed = 0
            var totalBytes = 0L
            SevenZFile(source).use { sevenZ ->
                var entry = sevenZ.nextEntry
                while (entry != null) {
                    try {
                        if (processed >= ArchiveLimits.MAX_7Z_ENTRIES) {
                            throw IllegalStateException(
                                "Архив содержит больше ${ArchiveLimits.MAX_7Z_ENTRIES} записей — распаковка прервана."
                            )
                        }
                        processed++
                        val safeName = support.sanitizeEntryName(entry.name)
                        when {
                            safeName == null -> skipped++
                            entry.isDirectory ->
                                support.mkdirsTracked(File(destDir, safeName), createdDirs)

                            !entry.hasStream() -> skipped++
                            else -> {
                                // Separator-aware containment (zip/tar-slip guard).
                                val out = File(destDir, safeName)
                                require(
                                    out.canonicalPath == destCanon ||
                                        out.canonicalPath.startsWith(destPrefix)
                                ) { "Недопустимый путь в архиве: ${entry.name}." }
                                out.parentFile?.let { support.mkdirsTracked(it, createdDirs) }
                                val existedBefore = out.exists()
                                sevenZ.getInputStream(entry).use { ins ->
                                    out.outputStream().use { os ->
                                        val buffer = ByteArray(ArchiveLimits.COPY_BUFFER_SIZE)
                                        while (true) {
                                            val read = ins.read(buffer)
                                            if (read < 0) break
                                            totalBytes += read
                                            if (totalBytes > ArchiveLimits.MAX_7Z_UNCOMPRESSED_BYTES) {
                                                throw IllegalStateException(
                                                    "Распакованный архив превышает лимит " +
                                                        "${ArchiveLimits.MAX_7Z_UNCOMPRESSED_BYTES / (1024 * 1024)} МиБ — распаковка прервана."
                                                )
                                            }
                                            os.write(buffer, 0, read)
                                        }
                                    }
                                }
                                if (!existedBefore) createdFiles.add(out)
                                count++
                            }
                        }
                    } finally {
                        // Advance past the current entry in every case, so an entry that
                        // was skipped or only partly read can never wedge the loop.
                        entry = sevenZ.nextEntry
                    }
                }
            }

            if (count == 0) {
                support.cleanupPartial(createdFiles, createdDirs)
                ToolResult(false, "Архив пуст или не содержит файлов.")
            } else {
                val skippedNote =
                    if (skipped > 0) " Пропущено небезопасных записей: $skipped." else ""
                ToolResult(true, "Распаковано $count файлов в ${destDir.absolutePath}.$skippedNote")
            }
        } catch (e: Exception) {
            support.cleanupPartial(createdFiles, createdDirs)
            ToolResult(
                false,
                "Ошибка распаковки 7z: ${e.message ?: e::class.simpleName}. " +
                    "Частично распакованные файлы удалены."
            )
        } catch (e: LinkageError) {
            support.cleanupPartial(createdFiles, createdDirs)
            ToolResult(
                false,
                "Ошибка распаковки 7z: отсутствует зависимость среды выполнения " +
                    "(${e.message ?: e::class.simpleName}). Частично распакованные файлы удалены."
            )
        }
    }
}
