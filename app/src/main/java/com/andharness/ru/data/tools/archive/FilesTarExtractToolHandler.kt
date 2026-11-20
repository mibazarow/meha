package com.andharness.ru.data.tools.archive

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `files_tar_extract`: extracts a TAR (optionally gzip-compressed) archive into a destination
 * directory, guarding against tar-slip, oversized "archive bombs", and rolling back exactly the
 * files/dirs it created on failure.
 *
 * Apache Commons Compress reference (artifactId org.apache.commons:commons-compress):
 * - TarArchiveInputStream —
 *   https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/tar/TarArchiveInputStream.html
 *   (getNextEntry(): "Gets the next TarArchiveEntry in this stream"; there is no closeArchiveEntry
 *   on the input stream — only TarArchiveOutputStream has it)
 */
internal class FilesTarExtractToolHandler(
    private val support: ArchiveSupport,
) : ToolHandler {

    override val name = "files_tar_extract"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        // Files/dirs this call actually created, so a breach or I/O error can roll
        // back only our own output and never a pre-existing tree.
        val createdFiles = mutableListOf<File>()
        val createdDirs = mutableListOf<File>()
        return try {
            val source = File(support.path(arguments, "source"))
            if (!source.isFile) return ToolResult(false, "Не файл: ${source.name}.")
            // The destination goes through path(), which applies the canonical
            // containment check against the tool root.
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
            support.openTarStream(source).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    try {
                        if (processed >= ArchiveLimits.MAX_TAR_ENTRIES) {
                            throw IllegalStateException(
                                "Архив содержит больше ${ArchiveLimits.MAX_TAR_ENTRIES} записей — распаковка прервана."
                            )
                        }
                        processed++
                        val safeName = support.sanitizeEntryName(entry.name)
                        if (safeName == null) {
                            skipped++
                        } else {
                            // Separator-aware containment (Tar-Slip guard): a sibling
                            // sharing the prefix must NOT pass, hence destPrefix.
                            // java.io.File canonical path reference:
                            // https://developer.android.com/reference/java/io/File#getCanonicalPath()
                            val out = File(destDir, safeName)
                            require(
                                out.canonicalPath == destCanon ||
                                    out.canonicalPath.startsWith(destPrefix)
                            ) { "Недопустимый путь в архиве: ${entry.name}." }
                            if (entry.isDirectory) {
                                support.mkdirsTracked(out, createdDirs)
                            } else {
                                out.parentFile?.let { support.mkdirsTracked(it, createdDirs) }
                                val existedBefore = out.exists()
                                out.outputStream().use { os ->
                                    val buffer = ByteArray(ArchiveLimits.COPY_BUFFER_SIZE)
                                    while (true) {
                                        val read = tar.read(buffer)
                                        if (read < 0) break
                                        totalBytes += read
                                        if (totalBytes > ArchiveLimits.MAX_TAR_UNCOMPRESSED_BYTES) {
                                            throw IllegalStateException(
                                                "Распакованный архив превышает лимит " +
                                                    "${ArchiveLimits.MAX_TAR_UNCOMPRESSED_BYTES / (1024 * 1024)} МиБ — распаковка прервана."
                                            )
                                        }
                                        os.write(buffer, 0, read)
                                    }
                                }
                                if (!existedBefore) createdFiles.add(out)
                                count++
                            }
                        }
                    } finally {
                        // TarArchiveInputStream has no closeArchiveEntry() (that method
                        // exists only on TarArchiveOutputStream), so advancing to the next
                        // entry in a finally is what guarantees the loop can never wedge on
                        // a half-read entry: getNextEntry() drains/positions the stream past
                        // the current entry in every case.
                        entry = tar.nextEntry
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
                "Ошибка распаковки: ${e.message ?: e::class.simpleName}. " +
                    "Частично распакованные файлы удалены."
            )
        } catch (e: LinkageError) {
            support.cleanupPartial(createdFiles, createdDirs)
            ToolResult(
                false,
                "Ошибка распаковки: отсутствует зависимость среды выполнения " +
                    "(${e.message ?: e::class.simpleName}). Частично распакованные файлы удалены."
            )
        }
    }
}
