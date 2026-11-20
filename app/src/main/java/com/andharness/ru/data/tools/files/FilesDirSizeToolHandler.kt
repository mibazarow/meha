package com.andharness.ru.data.tools.files

import android.os.StatFs
import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/** `files_dir_size`: reports the byte/file count of a path and the free space of its volume. */
internal class FilesDirSizeToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_dir_size"
    override suspend fun execute(arguments: JsonObject): ToolResult = try {
        val base = io.root()
        // The path goes through path(), which resolves it against every allowed root and
        // rejects `..` and any canonical escape from those roots (A1).
        val target = File(io.path(arguments, "path"))
        val relative = target.relativeToOrSelf(base).path.ifEmpty { "." }
        if (!target.exists()) {
            ToolResult(false, "Не найдено: «$relative».")
        } else {
            val fileCount: Int
            val totalBytes: Long
            val kind: String
            if (target.isDirectory) {
                // Kotlin stdlib File.walkTopDown — a lazily-evaluated depth-first sequence
                // over the file tree; summing File.length() over its regular files is O(n)
                // and allocates nothing per entry.
                // https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io/
                var files = 0
                var bytes = 0L
                for (child in target.walkTopDown()) {
                    if (child.isFile) {
                        files++
                        bytes += child.length()
                    }
                }
                fileCount = files
                totalBytes = bytes
                kind = "Папка"
            } else {
                fileCount = 1
                totalBytes = target.length()
                kind = "Файл"
            }

            // android.os.StatFs reports the block-space picture of the filesystem that
            // holds the path (it stats the volume, not the entry itself):
            // StatFs(path).availableBytes -> bytes available to this app;
            // StatFs(path).totalBytes -> total bytes on the filesystem.
            // java.io.File#length —
            // https://developer.android.com/reference/java/io/File#length()
            // android.os.StatFs —
            // https://developer.android.com/reference/android/os/StatFs
            val stat = StatFs(target.absolutePath)
            val freeBytes = stat.availableBytes
            val volumeBytes = stat.totalBytes
            ToolResult(
                true,
                "$kind «$relative»: ${formatBytes(totalBytes)} ($totalBytes байт), " +
                    "файлов: $fileCount.\n" +
                    "Свободно на томе: ${formatBytes(freeBytes)} ($freeBytes байт) " +
                    "из ${formatBytes(volumeBytes)} ($volumeBytes байт)."
            )
        }
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }
}
