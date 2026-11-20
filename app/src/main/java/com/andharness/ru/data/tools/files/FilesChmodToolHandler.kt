package com.andharness.ru.data.tools.files

import com.andharness.ru.data.tools.boolOrNull
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `files_chmod`: best-effort permission change on a file/directory via `File.setReadable` /
 * `setWritable` / `setExecutable`. Every call's boolean result is reported verbatim — the tool never
 * pretends a permission changed when the platform refused it.
 *
 * Android honesty: under scoped storage (and on modern Android generally) an app may change the mode
 * bits only of files it owns; for other apps' files these calls typically return `false` or have no
 * observable effect. The result says so explicitly so a `false` is never hidden behind a success.
 * Only the flags explicitly provided are touched, so this is never broader than the request.
 *
 * References:
 * - `File#setReadable(boolean, boolean)` —
 *   https://developer.android.com/reference/java/io/File#setReadable(boolean,%20boolean)
 * - `File#setWritable(boolean, boolean)` —
 *   https://developer.android.com/reference/java/io/File#setWritable(boolean,%20boolean)
 * - `File#setExecutable(boolean, boolean)` —
 *   https://developer.android.com/reference/java/io/File#setExecutable(boolean,%20boolean)
 * - Scoped storage / all-files access —
 *   https://developer.android.com/training/data-storage/manage-all-files
 */
internal class FilesChmodToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_chmod"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val target = File(io.path(arguments, "path"))
            if (!target.exists()) return ToolResult(false, "Не найдено: ${target.absolutePath}")

            val readable = arguments.boolOrNull("readable")
            val writable = arguments.boolOrNull("writable")
            val executable = arguments.boolOrNull("executable")
            if (readable == null && writable == null && executable == null) {
                return ToolResult(false, "Укажите хотя бы одно из: readable, writable, executable.")
            }
            val applyToAll = arguments.boolOrNull("apply_to_all") ?: true
            val recursive = arguments.boolOrNull("recursive") ?: false
            val ownerOnly = !applyToAll

            val files = if (recursive && target.isDirectory) {
                target.walkTopDown().toList()
            } else {
                listOf(target)
            }

            val applied = ArrayList<String>()
            val refused = ArrayList<String>()
            var anyApplied = false
            for (file in files) {
                val flags = ArrayList<String>()
                readable?.let { value ->
                    val ok = file.setReadable(value, ownerOnly)
                    anyApplied = anyApplied || ok
                    flags += "readable=$value:${if (ok) "ок" else "отказ"}"
                }
                writable?.let { value ->
                    val ok = file.setWritable(value, ownerOnly)
                    anyApplied = anyApplied || ok
                    flags += "writable=$value:${if (ok) "ок" else "отказ"}"
                }
                executable?.let { value ->
                    val ok = file.setExecutable(value, ownerOnly)
                    anyApplied = anyApplied || ok
                    flags += "executable=$value:${if (ok) "ок" else "отказ"}"
                }
                val line = "«${file.name}»: ${flags.joinToString(", ")}"
                if (flags.any { it.endsWith("ок") }) applied += line else refused += line
            }

            val scope = if (applyToAll) "для всех" else "только владелец"
            val head = "Смена прав ($scope) в ${files.size} объект(ах)."
            val body = buildString {
                append(head)
                if (applied.isNotEmpty()) {
                    append(" Применено:\n")
                    append(applied.take(MAX_LISTED).joinToString("\n"))
                    if (applied.size > MAX_LISTED) append("\n… (ещё ${applied.size - MAX_LISTED})")
                }
                if (refused.isNotEmpty()) {
                    append("\nОтказано системой:\n")
                    append(refused.take(MAX_LISTED).joinToString("\n"))
                    if (refused.size > MAX_LISTED) append("\n… (ещё ${refused.size - MAX_LISTED})")
                }
                append("\nНа Android под scoped storage смена прав обычно возможна только для файлов самого приложения.")
            }

            // Success only when at least one requested flag was actually applied.
            ToolResult(anyApplied, body)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    private companion object {
        private const val MAX_LISTED = 50
    }
}
