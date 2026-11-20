package com.andharness.ru.data.tools.archive

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.github.junrar.Archive
import kotlinx.serialization.json.JsonObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * `files_rar_extract`: extracts a RAR archive into a destination directory (read-only format),
 * guarding against zip/tar-slip, oversized "archive bombs", and rolling back exactly the files/dirs
 * it created on failure.
 *
 * junrar reference (artifactId com.github.junrar:junrar, read-only RAR extraction):
 * - Archive — https://github.com/junrar/junrar (v7.5.5: Archive(File), getFileHeaders(),
 *   extractFile(FileHeader, OutputStream)); the library can only READ RAR archives, there is no
 *   RAR writer, hence no files_rar_create tool.
 * - FileHeader — https://github.com/junrar/junrar/blob/v7.5.5/src/main/java/com/github/junrar/rarfile/FileHeader.java
 *   (isDirectory / isFileHeader / getFileName() — since 7.2.0 getFileName() already returns the
 *   Unicode name when present, so the deprecated getFileNameW() is intentionally not used; the
 *   getFullUnpackSize() value is the declared uncompressed size).
 */
internal class FilesRarExtractToolHandler(
    private val support: ArchiveSupport,
) : ToolHandler {

    override val name = "files_rar_extract"

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
            var declaredBytes = 0L
            // junrar is READ-ONLY: open Archive(File), iterate fileHeaders and stream each
            // file into its destination via extractFile(header, outputStream).
            Archive(source).use { archive ->
                for (header in archive.fileHeaders) {
                    if (processed >= ArchiveLimits.MAX_RAR_ENTRIES) {
                        throw IllegalStateException(
                            "Архив содержит больше ${ArchiveLimits.MAX_RAR_ENTRIES} записей — распаковка прервана."
                        )
                    }
                    processed++
                    // Only real files are extracted; directory and non-file headers are
                    // skipped (never treated as a file to write).
                    if (header.isDirectory || !header.isFileHeader) continue
                    val safeName = support.sanitizeEntryName(header.fileName ?: "")
                    if (safeName == null) {
                        skipped++
                        continue
                    }
                    // Separator-aware containment (zip/tar-slip guard).
                    val out = File(destDir, safeName)
                    require(
                        out.canonicalPath == destCanon ||
                            out.canonicalPath.startsWith(destPrefix)
                    ) { "Недопустимый путь в архиве: ${header.fileName}." }
                    val declared = header.fullUnpackSize
                    if (declared > 0) {
                        declaredBytes += declared
                        if (declaredBytes > ArchiveLimits.MAX_RAR_UNCOMPRESSED_BYTES) {
                            throw IllegalStateException(
                                "Распакованный архив превышает лимит " +
                                    "${ArchiveLimits.MAX_RAR_UNCOMPRESSED_BYTES / (1024 * 1024)} МиБ — распаковка прервана."
                            )
                        }
                    }
                    out.parentFile?.let { support.mkdirsTracked(it, createdDirs) }
                    val existedBefore = out.exists()
                    BufferedOutputStream(
                        FileOutputStream(out),
                        ArchiveLimits.COPY_BUFFER_SIZE
                    ).use { os ->
                        archive.extractFile(header, os)
                    }
                    if (!existedBefore) createdFiles.add(out)
                    count++
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
                "Ошибка распаковки RAR: ${e.message ?: e::class.simpleName}. " +
                    "Частично распакованные файлы удалены."
            )
        } catch (e: LinkageError) {
            support.cleanupPartial(createdFiles, createdDirs)
            ToolResult(
                false,
                "Ошибка распаковки RAR: отсутствует зависимость среды выполнения " +
                    "(${e.message ?: e::class.simpleName}). Частично распакованные файлы удалены."
            )
        }
    }
}
