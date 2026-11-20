package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** `files_unzip`: extracts a ZIP safely (Zip-Slip guard, bomb limits, rollback of own output). */
internal class FilesUnzipToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_unzip"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        // Files/dirs this call actually created, so a breach or I/O error can roll
        // back only our own output and never a pre-existing tree.
        val createdFiles = mutableListOf<File>()
        val createdDirs = mutableListOf<File>()
        return try {
            val src = File(io.path(arguments, "source"))
            if (!src.isFile) return ToolResult(false, "Не файл: ${src.name}.")
            // The destination goes through path(), which already applies a separator-aware
            // canonical containment check against every allowed root.
            val destDir = File(io.path(arguments, "destination"))
            destDir.mkdirs()
            if (!destDir.isDirectory) {
                return ToolResult(false, "Не удалось создать каталог назначения: ${destDir.absolutePath}.")
            }
            val destCanon = destDir.canonicalPath
            val destPrefix = destCanon + File.separator
            var count = 0
            var skipped = 0
            var processed = 0
            var totalBytes = 0L
            // ZipInputStream reference:
            // https://developer.android.com/reference/java/util/zip/ZipInputStream
            ZipInputStream(BufferedInputStream(src.inputStream())).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    try {
                        if (processed >= MAX_UNZIP_ENTRIES) {
                            throw IllegalStateException(
                                "Архив содержит больше $MAX_UNZIP_ENTRIES записей — распаковка прервана."
                            )
                        }
                        processed++
                        val safeName = sanitizeZipEntryName(entry.name)
                        if (safeName == null) {
                            skipped++
                        } else {
                            // Separator-aware containment (Zip-Slip guard): a sibling
                            // sharing the prefix must NOT pass, hence destPrefix.
                            // java.io.File canonical path reference:
                            // https://developer.android.com/reference/java/io/File#getCanonicalPath()
                            val out = File(destDir, safeName)
                            require(out.canonicalPath == destCanon || out.canonicalPath.startsWith(destPrefix)) {
                                "Недопустимый путь в архиве (Zip-Slip): ${entry.name}."
                            }
                            if (entry.isDirectory) {
                                mkdirsTracked(out, createdDirs)
                            } else {
                                out.parentFile?.let { mkdirsTracked(it, createdDirs) }
                                val existedBefore = out.exists()
                                out.outputStream().use { os ->
                                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                                    while (true) {
                                        val read = zis.read(buffer)
                                        if (read < 0) break
                                        totalBytes += read
                                        if (totalBytes > MAX_UNZIP_BYTES) {
                                            throw IllegalStateException(
                                                "Распакованный архив превышает лимит " +
                                                    "${MAX_UNZIP_BYTES / (1024 * 1024)} МиБ — распаковка прервана."
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
                        // Always close the current entry so a malformed or truncated
                        // entry cannot wedge the stream loop.
                        zis.closeEntry()
                    }
                    entry = zis.nextEntry
                }
            }
            if (count == 0) {
                ToolResult(false, "Архив пуст или не содержит файлов.")
            } else {
                val skippedNote =
                    if (skipped > 0) " Пропущено небезопасных записей: $skipped." else ""
                ToolResult(true, "Распаковано $count файлов в ${destDir.absolutePath}.$skippedNote")
            }
        } catch (e: Exception) {
            cleanupPartial(createdFiles, createdDirs)
            ToolResult(
                false,
                "Ошибка распаковки: ${e.message ?: e::class.simpleName}. " +
                    "Частично распакованные файлы удалены."
            )
        }
    }

    /**
     * Turns a raw [ZipEntry] name into a safe relative path inside the destination, or returns
     * null when the entry must be skipped. Backslashes are normalised to `/` (some archivers write
     * Windows separators), a leading `/` is stripped, empty/`.` segments are dropped, and any `..`
     * segment rejects the entry so it can never escape the destination directory.
     * Reference: https://developer.android.com/reference/java/util/zip/ZipEntry
     */
    private fun sanitizeZipEntryName(raw: String): String? {
        val normalized = raw.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return null
        val safe = mutableListOf<String>()
        for (part in normalized.split('/')) {
            when (part) {
                "", "." -> continue
                ".." -> return null
                else -> safe.add(part)
            }
        }
        if (safe.isEmpty()) return null
        return safe.joinToString("/")
    }

    /**
     * Creates [dir] (and any missing parents) and records every directory level this call actually
     * created into [createdDirs] (root-first), so a failed extraction can remove exactly its own
     * output and nothing pre-existing.
     * Reference: https://developer.android.com/reference/java/io/File#mkdirs()
     */
    private fun mkdirsTracked(dir: File, createdDirs: MutableList<File>) {
        if (dir.isDirectory) return
        val missing = mutableListOf<File>()
        var cur: File? = dir
        while (cur != null && !cur.exists()) {
            missing.add(cur)
            cur = cur.parentFile
        }
        if (dir.mkdirs()) createdDirs.addAll(missing.asReversed())
    }

    /**
     * Deletes only the files and directories this extraction call actually created. Files go first,
     * then directories deepest-first; [File.delete] removes a directory only when it is empty, so
     * pre-existing content is never destroyed.
     */
    private fun cleanupPartial(createdFiles: List<File>, createdDirs: List<File>) {
        createdFiles.asReversed().forEach { runCatching { it.delete() } }
        createdDirs.asReversed().forEach { runCatching { it.delete() } }
    }

    private companion object {
        // files_unzip limits: bound entry count and total uncompressed size against zip bombs.
        private const val MAX_UNZIP_ENTRIES = 10_000
        private const val MAX_UNZIP_BYTES = 2L * 1024L * 1024L * 1024L
        private const val COPY_BUFFER_SIZE = 8 * 1024
    }
}
