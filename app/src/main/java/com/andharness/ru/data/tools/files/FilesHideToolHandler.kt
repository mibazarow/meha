package com.andharness.ru.data.tools.files

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import com.andharness.ru.data.tools.boolOrNull
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * `files_hide`: hides/shows content on the system MediaStore. Android has no per-file "hidden" flag
 * that a normal app may set, so the tool uses the platform conventions honestly:
 * - a **directory** is hidden by creating an empty `.nomedia` file inside it (and, with
 *   `recursive`, inside every subdirectory), and shown again by removing it;
 * - a **file** is hidden by renaming it with a leading dot (`.name`) and shown by removing that dot,
 *   which is how `java.io.File`/the gallery expose hidden names.
 * After a change the affected path is re-scanned so MediaStore drops/updates stale rows. The result
 * states exactly what was done and never claims a change that did not happen.
 *
 * Scope-storage honesty: this can only hide things the app can write. Under scoped storage an app
 * cannot rename or write into another app's private directory, so those paths fail honestly instead
 * of pretending to hide them.
 *
 * References:
 * - `.nomedia` convention (media files are hidden from the scanner in such directories) —
 *   https://developer.android.com/training/data-storage/shared/media
 * - `java.io.File#renameTo(File)` — https://developer.android.com/reference/java/io/File#renameTo(java.io.File)
 * - `MediaScannerConnection.scanFile(Context, String[], String[], OnScanCompletedListener)` —
 *   https://developer.android.com/reference/android/media/MediaScannerConnection#scanFile(android.content.Context,%20java.lang.String%5B%5D,%20java.lang.String%5B%5D,%20android.media.MediaScannerConnection.OnScanCompletedListener)
 */
internal class FilesHideToolHandler(
    private val context: Context,
    private val io: FileToolIo,
) : ToolHandler {
    override val name = "files_hide"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val target = File(io.path(arguments, "path"))
            if (!target.exists()) return ToolResult(false, "Не найдено: ${target.absolutePath}")
            val hide = arguments.boolOrNull("hide") ?: true
            val recursive = arguments.boolOrNull("recursive") ?: false

            if (target.isDirectory) {
                hideDirectory(target, hide, recursive)
            } else {
                hideFile(target, hide)
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    private suspend fun hideDirectory(dir: File, hide: Boolean, recursive: Boolean): ToolResult {
        val dirs = if (recursive) {
            dir.walkTopDown().filter { it.isDirectory }.toList()
        } else {
            listOf(dir)
        }
        val changed = ArrayList<File>()
        val alreadyInState = ArrayList<File>()
        val failed = ArrayList<String>()
        for (current in dirs) {
            val marker = File(current, NOMEDIA)
            if (hide) {
                if (marker.exists()) {
                    alreadyInState += current
                } else if (marker.createNewFile()) {
                    changed += current
                } else {
                    failed += "«${current.name}» (не удалось создать $NOMEDIA)"
                }
            } else {
                if (!marker.exists()) {
                    alreadyInState += current
                } else if (marker.delete()) {
                    changed += current
                } else {
                    failed += "«${current.name}» (не удалось удалить $NOMEDIA)"
                }
            }
        }

        val verify = if (changed.isEmpty()) false else rescan(changed.map { it.absolutePath })
        val action = if (hide) "скрыто" else "показано"
        val parts = ArrayList<String>()
        parts += "Папка «${dir.name}»: $action в ${changed.size} каталог(ах) (маркер $NOMEDIA)."
        if (alreadyInState.isNotEmpty()) parts += "Уже в нужном состоянии: ${alreadyInState.size}."
        if (failed.isNotEmpty()) parts += "Не удалось: ${failed.joinToString("; ")}."
        parts += rescanNote(verify)
        return ToolResult(failed.size < dirs.size || changed.isNotEmpty(), parts.joinToString(" "))
    }

    private suspend fun hideFile(file: File, hide: Boolean): ToolResult {
        val name = file.name
        val hidden = name.startsWith(".")
        if (hide == hidden) {
            return ToolResult(
                true,
                "Файл «$name» уже ${if (hide) "скрыт (имя начинается с точки)" else "не скрыт"}."
            )
        }
        val newName = if (hide) ".$name" else name.removePrefix(".")
        if (newName.isEmpty()) return ToolResult(false, "Невозможно переименовать «$name».")
        val renamed = File(file.parentFile, newName)
        if (renamed.exists()) return ToolResult(false, "Цель уже существует: «${renamed.name}». Переименование отменено.")
        if (!file.renameTo(renamed)) {
            return ToolResult(
                false,
                "Не удалось переименовать «$name» в «$newName» (под scoped storage нельзя менять " +
                    "файлы, которыми приложение не владеет)."
            )
        }
        val scanned = rescan(listOf(renamed.absolutePath))
        return ToolResult(
            true,
            "Файл «$name» → «$newName» (${if (hide) "скрыт" else "показан"}). ${rescanNote(scanned)}"
        )
    }

    /** Best-effort MediaStore re-scan of [paths]; true when MediaStore returned at least one Uri. */
    private suspend fun rescan(paths: List<String>): Boolean {
        if (paths.isEmpty()) return false
        return try {
            val remaining = AtomicInteger(paths.size)
            val uris = ConcurrentLinkedQueue<Uri>()
            val finished = CompletableDeferred<Unit>()
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, uri ->
                if (uri != null) uris += uri
                if (remaining.decrementAndGet() <= 0) finished.complete(Unit)
            }
            val timedOut = withTimeoutOrNull(SCAN_TIMEOUT_MS) { finished.await() } == null
            if (timedOut) false else uris.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun rescanNote(scanned: Boolean): String =
        if (scanned) "MediaStore пересканирован." else "Пересканирование MediaStore не подтверждено (лучшее усилие)."

    private companion object {
        private const val NOMEDIA = ".nomedia"
        private const val SCAN_TIMEOUT_MS = 10_000L
    }
}
