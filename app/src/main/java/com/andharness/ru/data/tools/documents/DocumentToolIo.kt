package com.andharness.ru.data.tools.documents

import android.content.Context
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.resolveSafePath
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream

/**
 * Shared filesystem/argument plumbing for the document tools: resolves tool-root-relative paths,
 * parses optional JSON arguments and performs atomic writes with `.bak` backups.
 */
internal class DocumentToolIo(
    private val context: Context,
) {

    /** The `.bak` backup path used by [atomicWrite] for [file]. */
    fun backupFile(file: File): File = File(file.parentFile, "${file.name}.bak")

    /**
     * Restores the previous version from the `.bak` backup made by [atomicWrite]. The rename
     * consumes the backup, so a successful restore leaves no `.bak` behind.
     */
    fun restoreBackup(file: File): Boolean {
        val backup = backupFile(file)
        if (!backup.isFile) return false
        file.delete()
        return backup.renameTo(file)
    }

    /**
     * Removes the `.bak` backup made by [atomicWrite] once the write was verified. Returns a note
     * for the success message, or `null` when this call left no backup. A failed deletion is
     * reported honestly instead of failing an operation that actually succeeded.
     */
    fun cleanupBackup(file: File): String? {
        val backup = backupFile(file)
        if (!backup.isFile) return null
        return if (backup.delete()) {
            "Резервная копия удалена после проверки."
        } else {
            "Правка применена, но резервную копию «${backup.name}» удалить не удалось."
        }
    }

    /**
     * Resolves [raw] inside the tool root and confirms PdfBox can actually open it as a PDF,
     * reporting the page count. A missing file, a path outside the root and a damaged/non-PDF file
     * are all honest, user-facing failures.
     */
    fun resolvePdfSource(raw: String): PdfSource {
        val file = try {
            resolve(raw)
        } catch (e: IllegalArgumentException) {
            return PdfSource.Failure(e.message ?: "Некорректный путь: «$raw».")
        }
        if (!file.isFile) {
            return PdfSource.Failure("Файл не найден: ${file.absolutePath}")
        }
        return try {
            PDDocument.load(file).use { PdfSource.Ok(file, it.numberOfPages) }
        } catch (e: Exception) {
            PdfSource.Failure("Не удалось открыть PDF: ${file.absolutePath} (${e.message})")
        }
    }

    /**
     * Resolves the output path and enforces the shared no-clobber rule: an existing file is refused
     * unless the caller passed `overwrite=true`. Refusing up front keeps every input untouched.
     */
    fun resolveDestination(raw: String, overwrite: Boolean): Destination {
        val file = try {
            resolve(raw)
        } catch (e: IllegalArgumentException) {
            return Destination.Failure(e.message ?: "Некорректный путь: «$raw».")
        }
        if (file.isDirectory) {
            return Destination.Failure("Путь указывает на каталог: ${file.absolutePath}")
        }
        if (file.exists() && !overwrite) {
            return Destination.Failure(
                "Файл уже существует: «${file.absolutePath}». Перезапись отключена " +
                    "(overwrite=false)."
            )
        }
        return Destination.Ok(file)
    }

    /**
     * Parses a 1-based page selection: a single page (`5`) or an inclusive range (`1-3`). Returns
     * `null` for anything malformed or out of `1..total`, so the caller can report an honest error.
     */
    fun parsePageRange(spec: String, total: Int): IntRange? {
        val trimmed = spec.trim()
        val single = trimmed.toIntOrNull()
        if (single != null) {
            return if (single in 1..total) single..single else null
        }
        val parts = trimmed.split('-')
        if (parts.size != 2) return null
        val start = parts[0].trim().toIntOrNull() ?: return null
        val end = parts[1].trim().toIntOrNull() ?: return null
        if (start < 1 || end < start || end > total) return null
        return start..end
    }

    /** Optional boolean argument; anything absent or unparsable is `false` (the safe default). */
    fun booleanArgument(arguments: JsonObject, key: String): Boolean =
        (arguments[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

    /**
     * Renames the verified [temp] file over [destination] (same directory → atomic on one volume).
     * An existing destination is kept as a `.bak` first and restored if the rename fails, so an
     * overwrite can never lose the previous file; [cleanupBackup] drops the backup after success.
     */
    fun commitTemp(temp: File, destination: File): String? {
        val existed = destination.exists()
        if (existed) {
            runCatching {
                val backup = backupFile(destination)
                backup.delete()
                destination.renameTo(backup)
            }
        }
        if (!temp.renameTo(destination)) {
            temp.delete()
            if (existed) restoreBackup(destination)
            return "Не удалось атомарно заменить файл: «${destination.absolutePath}»."
        }
        return null
    }

    /**
     * Writes [bytes] into a temp file in the SAME directory, fsyncs it, then renames it over the
     * target (a same-volume rename is atomic). Returns an error message, or `null` on success.
     */
    fun atomicWrite(file: File, bytes: ByteArray): String? {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(temp).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
        } catch (e: Exception) {
            temp.delete()
            return "Не удалось записать временный файл: ${e.message}"
        }

        // Best-effort backup of the previous version so an overwrite stays recoverable.
        if (file.exists()) {
            runCatching {
                val backup = File(file.parentFile, "${file.name}.bak")
                backup.delete()
                file.renameTo(backup)
            }
        }

        if (!temp.renameTo(file)) {
            temp.delete()
            return "Не удалось атомарно заменить файл: «${file.absolutePath}»."
        }
        return null
    }

    fun stringArgument(arguments: JsonObject, key: String): String? =
        (arguments[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    fun intArgument(arguments: JsonObject, key: String): Int? =
        (arguments[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

    /** Resolves [raw] inside the tool root; throws for a path outside it. */
    fun resolve(raw: String): File = resolveSafePath(context.effectiveToolRoot(), raw)

}
