package com.andharness.ru.data.tools.archive

import android.content.Context
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.resolveSafePath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Path resolution and archive-stream helpers shared by the archive tools (files_tar_create /
 * files_tar_extract, files_7z_create / files_7z_extract, files_rar_extract).
 *
 * Path arguments mirror the files-handler approach: the base directory resolves via
 * [effectiveToolRoot] (the real shared-storage root when MANAGE_EXTERNAL_STORAGE is granted,
 * otherwise the app-private dir) and every argument is validated/contained by [resolveSafePath],
 * which rejects `..`, NUL bytes and any canonical escape from the root.
 *
 * References:
 * - `java.io.File` canonical path / mkdirs —
 *   https://developer.android.com/reference/java/io/File
 * - GzipCompressorInputStream (RFC 1952 gzip magic 0x1f 0x8b) —
 *   https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/compressors/gzip/GzipCompressorInputStream.html
 * - TarArchiveInputStream / SevenZFile entry iteration —
 *   https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/tar/TarArchiveInputStream.html
 */
internal class ArchiveSupport(private val context: Context) {

    /** Current tool root: shared storage when MANAGE_EXTERNAL_STORAGE is granted, else app-private. */
    fun root(): File = context.effectiveToolRoot()

    /**
     * Validates and resolves a required path argument against the tool root. Returns the canonical
     * ABSOLUTE path, so anything outside the root (absolute escape, `..`, NUL) is rejected here
     * rather than acted on.
     */
    fun path(arguments: JsonObject, key: String): String {
        val raw = (arguments[key] as? JsonPrimitive)?.content
        if (raw.isNullOrEmpty()) {
            throw IllegalArgumentException("Отсутствует обязательный аргумент: $key")
        }
        return resolveSafePath(root(), raw).absolutePath
    }

    /**
     * Relative, TAR-safe entry name: never absolute, never contains `.`/`..` segments.
     */
    fun entryNameOf(file: File, baseDir: File): String =
        file.relativeToOrSelf(baseDir).path
            .replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    /**
     * Turns a raw TAR entry name into a safe relative path inside the destination, or returns
     * null when nothing safe remains. Backslashes are normalised to `/`, a leading `/` is
     * stripped, and empty / `.` / `..` segments are dropped so an entry can never escape.
     */
    fun sanitizeEntryName(raw: String): String? {
        val normalized = raw.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return null
        val safe = mutableListOf<String>()
        for (part in normalized.split('/')) {
            when (part) {
                "", ".", ".." -> continue
                else -> safe.add(part)
            }
        }
        if (safe.isEmpty()) return null
        return safe.joinToString("/")
    }

    /**
     * Opens [file] as a TAR stream, transparently wrapping it in a GZIP decompressor when the
     * archive is gzip-compressed: detected by extension (`.gz` / `.tgz`) or by the gzip magic
     * number (0x1f 0x8b). RFC 1952 gzip magic:
     * https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/compressors/gzip/GzipCompressorInputStream.html
     */
    fun openTarStream(file: File): TarArchiveInputStream {
        val input = BufferedInputStream(FileInputStream(file))
        return if (isGzip(file)) TarArchiveInputStream(GzipCompressorInputStream(input))
        else TarArchiveInputStream(input)
    }

    private fun isGzip(file: File): Boolean {
        val lower = file.name.lowercase(Locale.ROOT)
        if (lower.endsWith(".gz") || lower.endsWith(".tgz")) return true
        return try {
            FileInputStream(file).use { ins ->
                val b0 = ins.read()
                val b1 = ins.read()
                b0 == 0x1f && b1 == 0x8b
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Reopens [file] (gzip-aware) and counts every TAR entry, used to post-verify a create. */
    fun countTarEntries(file: File): Int {
        var entries = 0
        openTarStream(file).use { tar ->
            while (tar.nextEntry != null) entries++
        }
        return entries
    }

    /** Reopens [file] as a 7z archive and counts every entry, used to post-verify a create. */
    fun countSevenZEntries(file: File): Int {
        var entries = 0
        SevenZFile(file).use { sevenZ ->
            while (sevenZ.nextEntry != null) entries++
        }
        return entries
    }

    /**
     * Creates [dir] (and any missing parents) and records every directory level this call actually
     * created into [createdDirs] (root-first), so a failed extraction can remove exactly its own
     * output and nothing pre-existing.
     * Reference: https://developer.android.com/reference/java/io/File#mkdirs()
     */
    fun mkdirsTracked(dir: File, createdDirs: MutableList<File>) {
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
    fun cleanupPartial(createdFiles: List<File>, createdDirs: List<File>) {
        createdFiles.asReversed().forEach { runCatching { it.delete() } }
        createdDirs.asReversed().forEach { runCatching { it.delete() } }
    }
}
