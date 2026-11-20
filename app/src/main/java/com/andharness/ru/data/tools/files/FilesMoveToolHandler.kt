package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/** `files_move`: renames if possible, otherwise copies-verifies-deletes across volumes. */
internal class FilesMoveToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_move"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            // Both ends go through path(), which resolves them against every allowed root
            // and rejects `..` and any canonical result escaping those roots (A1).
            val source = File(io.path(arguments, "source"))
            val destination = File(io.path(arguments, "destination"))

            if (!source.exists()) {
                return ToolResult(false, "Нечего перемещать: «${source.absolutePath}» не существует.")
            }
            // No silent overwrite: an existing destination is an honest error.
            if (destination.exists()) {
                return ToolResult(
                    false,
                    "Назначение уже существует: «${destination.absolutePath}». Перемещение отменено."
                )
            }

            // Fast path: a same-volume rename is atomic and cheap. It returns false (rather
            // than throwing) when the move crosses mounts or the destination is invalid.
            // File.renameTo —
            // https://developer.android.com/reference/java/io/File#renameTo(java.io.File)
            if (source.renameTo(destination)) {
                return if (!source.exists() && destination.exists()) {
                    ToolResult(
                        true,
                        "Перемещено (renameTo): ${source.absolutePath} → ${destination.absolutePath}"
                    )
                } else {
                    // renameTo claimed success but the on-disk state disagrees — report
                    // honestly instead of touching either path.
                    ToolResult(
                        false,
                        "renameTo вернул успех, но состояние не подтверждено. Проверьте вручную: " +
                            "${source.absolutePath} → ${destination.absolutePath}"
                    )
                }
            }

            // Slow path: rename failed (commonly internal <> SD/USB, or a missing destination
            // parent). Copy first, verify, and only then delete the source, so a failed copy
            // never loses data. Kotlin stdlib File.copyTo / File.copyRecursively —
            // https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io/
            destination.parentFile?.mkdirs()
            val copyAttempted = try {
                if (source.isDirectory) {
                    source.copyRecursively(destination, overwrite = false)
                } else {
                    source.copyTo(destination, overwrite = false)
                    true
                }
            } catch (e: Exception) {
                false
            }
            if (!copyAttempted) {
                if (destination.exists()) destination.deleteRecursively()
                return ToolResult(
                    false,
                    "Не удалось переместить (копирование не удалось): " +
                        "${source.absolutePath} → ${destination.absolutePath}"
                )
            }

            // Verify the copy actually landed before removing anything.
            val verified = if (source.isDirectory) {
                destination.isDirectory
            } else {
                destination.isFile && destination.length() == source.length()
            }
            if (!verified) {
                destination.deleteRecursively()
                return ToolResult(
                    false,
                    "Копирование не удалось проверить — исходный путь не удалён: ${source.absolutePath}"
                )
            }

            val deleted = if (source.isDirectory) source.deleteRecursively() else source.delete()
            if (deleted && !source.exists() && destination.exists()) {
                ToolResult(
                    true,
                    "Перемещено (копирование и удаление): ${source.absolutePath} → ${destination.absolutePath}"
                )
            } else {
                // The copy is valid but the source survived: never claim the move happened.
                ToolResult(
                    false,
                    "Скопировано в «${destination.absolutePath}», но исходный путь не удалён: ${source.absolutePath}"
                )
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
