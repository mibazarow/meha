package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** `files_rename`: renames an entry to a bare new name in the same directory. */
internal class FilesRenameToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_rename"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val base = io.root()
            // The source goes through path(), which resolves it against every allowed root
            // and rejects `..` and any canonical result escaping those roots (A1).
            val source = File(io.path(arguments, "path"))

            if (!source.exists()) {
                return ToolResult(
                    false,
                    "Нечего переименовывать: «${source.absolutePath}» не существует."
                )
            }

            val newName = (arguments["new_name"] as? JsonPrimitive)?.content.orEmpty()
            // A bare name only: non-blank, no path separators, not `.`/`..`, no NUL.
            if (newName.isBlank()) {
                return ToolResult(false, "Новое имя не указано (new_name пустое).")
            }
            if (newName.contains('/') || newName.contains('\\')) {
                return ToolResult(
                    false,
                    "Новое имя не должно содержать разделители пути: «$newName»."
                )
            }
            if (newName == "." || newName == "..") {
                return ToolResult(false, "Недопустимое новое имя: «$newName».")
            }
            if (newName.contains('\u0000')) {
                return ToolResult(false, "Новое имя содержит недопустимый символ NUL.")
            }

            val target = File(source.parentFile, newName)
            // No silent overwrite: an existing target is an honest error.
            if (target.exists()) {
                return ToolResult(
                    false,
                    "Имя уже занято: «${target.absolutePath}». Переименование отменено."
                )
            }

            // renameTo returns false (rather than throwing) on failure, so the on-disk
            // state is verified below instead of trusting its return value.
            // File.renameTo —
            // https://developer.android.com/reference/java/io/File#renameTo(java.io.File)
            val renamed = source.renameTo(target)
            if (!renamed || !target.exists() || source.exists()) {
                return ToolResult(
                    false,
                    "Не удалось переименовать: ${source.absolutePath} → ${target.absolutePath}"
                )
            }

            val relative = target.relativeToOrSelf(base).path
            ToolResult(true, "Переименовано: «${source.absolutePath}» → «$relative».")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
