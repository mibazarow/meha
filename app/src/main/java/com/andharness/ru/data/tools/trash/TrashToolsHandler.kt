package com.andharness.ru.data.tools.trash

import android.content.Context
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.trash.TrashEntry
import com.andharness.ru.domain.trash.TrashRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.data.tools.formatTimestamp

/**
 * Builds one [ToolHandler] per app-managed trash tool (TZ optional item `files_trash*`).
 *
 * The trash is a plain `.andharness_trash` directory inside the active tool root
 * ([effectiveToolRoot]); every entry is additionally recorded in [TrashRepository] so a trashed
 * item can be listed and restored by id. Path arguments are validated by [resolveSafePath], which
 * rejects `..` and any canonical escape from the root.
 *
 * java.io.File reference:
 * - https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/File.html
 *   (renameTo, mkdirs, delete, deleteRecursively; copyTo/copyRecursively are Kotlin stdlib
 *   extensions over File — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io/)
 */
class TrashToolsHandler(
    private val context: Context,
    private val trashRepository: TrashRepository,
) {

    private fun root(): File = context.effectiveToolRoot()

    /** The trash directory inside [root], created on demand. */
    private fun trashDir(root: File): File = File(root, TRASH_DIR_NAME).apply { mkdirs() }

    /**
     * Root-relative display path of [file], falling back to the bare name when the file does not
     * live under [root] (File.relativeTo throws IllegalArgumentException in that case).
     */
    private fun relativePath(root: File, file: File): String =
        runCatching { file.relativeTo(root).path }.getOrElse { file.name }

    /**
     * Moves [source] to [destination] with the same two-step strategy as `files_move`: a same-volume
     * rename first (atomic and cheap), otherwise copy → verify → delete source, so a failed copy can
     * never lose data. Returns true only when the on-disk state confirms the move.
     * File#renameTo —
     * https://developer.android.com/reference/java/io/File#renameTo(java.io.File)
     */
    private fun move(source: File, destination: File): Boolean {
        if (source.renameTo(destination)) {
            return !source.exists() && destination.exists()
        }
        destination.parentFile?.mkdirs()
        val copied = try {
            if (source.isDirectory) {
                source.copyRecursively(destination, overwrite = false)
            } else {
                source.copyTo(destination, overwrite = false)
                true
            }
        } catch (e: Exception) {
            false
        }
        if (!copied) {
            if (destination.exists()) destination.deleteRecursively()
            return false
        }
        // Verify the copy landed before removing anything.
        val verified = if (source.isDirectory) {
            destination.isDirectory
        } else {
            destination.isFile && destination.length() == source.length()
        }
        if (!verified) {
            destination.deleteRecursively()
            return false
        }
        val deleted = if (source.isDirectory) source.deleteRecursively() else source.delete()
        return deleted && !source.exists() && destination.exists()
    }

    /** Recursive byte size for directories, [File.length] for regular files. */
    private fun sizeOf(file: File): Long =
        if (file.isDirectory) {
            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            file.length()
        }

    /** True when [file] is [dir] itself or lies inside it (separator-aware containment). */
    private fun isInside(dir: File, file: File): Boolean {
        val dirPath = dir.canonicalFile.path
        val filePath = file.canonicalFile.path
        return filePath == dirPath || filePath.startsWith(dirPath + File.separator)
    }

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "files_trash"
            override suspend fun execute(arguments: JsonObject): ToolResult = try {
                val root = root()
                val trash = trashDir(root)
                // Relative paths still resolve against the primary root first; a removable volume
                // is additionally addressable by its absolute path.
                val target = resolveSafePath(
                    context.allowedToolRoots(),
                    (arguments["path"] as? JsonPrimitive)?.content.orEmpty()
                )
                if (!target.exists()) {
                    ToolResult(
                        false,
                        "Нечего перемещать: «${relativePath(root, target)}» не существует."
                    )
                } else if (isInside(trash, target)) {
                    // The trash directory and its contents are managed by the trash tools only.
                    ToolResult(
                        false,
                        "Объект уже находится в корзине приложения: «${relativePath(root, target)}»."
                    )
                } else {
                    val isDirectory = target.isDirectory
                    val sizeBytes = sizeOf(target)
                    val baseName = target.name
                    // Unique trash name; a collision (same millisecond + name) gets a numeric suffix.
                    var destination = File(trash, "${System.currentTimeMillis()}_$baseName")
                    var suffix = 0
                    while (destination.exists()) {
                        suffix++
                        destination = File(trash, "${System.currentTimeMillis()}_${baseName}_$suffix")
                    }

                    if (!move(target, destination) || target.exists() || !destination.exists()) {
                        ToolResult(
                            false,
                            "Не удалось переместить в корзину: «${relativePath(root, target)}»."
                        )
                    } else {
                        val originalPath = relativePath(root, target)
                        val trashPath = relativePath(root, destination)
                        // The row is written only after the move is verified on disk.
                        val id = trashRepository.add(
                            originalPath,
                            trashPath,
                            baseName,
                            isDirectory,
                            sizeBytes
                        )
                        ToolResult(
                            true,
                            "Перемещено в корзину: «$originalPath» (id=$id, ${destination.name}). " +
                                "Восстановить: files_trash_restore с id=$id."
                        )
                    }
                }
            } catch (e: Exception) {
                ToolResult(false, "Ошибка: ${e.message}")
            }
        },
        object : ToolHandler {
            override val name = "files_trash_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = try {
                val entries = trashRepository.list()
                if (entries.isEmpty()) {
                    ToolResult(true, "Корзина пуста.")
                } else {
                    val body = entries.joinToString("\n") { entry ->
                        "#${entry.id} ${entry.name} ← ${entry.originalPath} " +
                            "(${formatBytes(entry.sizeBytes)}, ${formatTime(entry.deletedAt)})"
                    }
                    ToolResult(true, "Записей в корзине: ${entries.size}.\n$body")
                }
            } catch (e: Exception) {
                ToolResult(false, "Ошибка: ${e.message}")
            }
        },
        object : ToolHandler {
            override val name = "files_trash_restore"
            override suspend fun execute(arguments: JsonObject): ToolResult = try {
                val rawId = (arguments["id"] as? JsonPrimitive)?.content
                val id = rawId?.trim()?.toLongOrNull()
                if (id == null) {
                    ToolResult(false, "Некорректный id: «${rawId.orEmpty()}». Укажите число из files_trash_list.")
                } else {
                    val entry = trashRepository.get(id)
                    if (entry == null) {
                        ToolResult(false, "Запись корзины #$id не найдена.")
                    } else {
                        restore(root(), entry)
                    }
                }
            } catch (e: Exception) {
                ToolResult(false, "Ошибка: ${e.message}")
            }
        },
        object : ToolHandler {
            override val name = "files_trash_empty"
            override suspend fun execute(arguments: JsonObject): ToolResult = try {
                val root = root()
                val entries = trashRepository.list()
                var removed = 0
                for (entry in entries) {
                    val file = File(root, entry.trashPath)
                    // Best-effort: an entry whose file is already gone still counts as cleared.
                    if (!file.exists()) {
                        removed++
                    } else if (file.deleteRecursively()) {
                        removed++
                    }
                }
                trashRepository.clear()
                if (trashRepository.list().isNotEmpty()) {
                    ToolResult(false, "Не удалось очистить корзину: часть записей осталась.")
                } else {
                    val failed = entries.size - removed
                    val note = if (failed > 0) " Ошибок удаления файлов: $failed." else ""
                    ToolResult(true, "Корзина очищена. Удалено записей: $removed.$note")
                }
            } catch (e: Exception) {
                ToolResult(false, "Ошибка: ${e.message}")
            }
        },
    )

    /** Moves a trash entry back to its original root-relative path (no overwrite). */
    private suspend fun restore(root: File, entry: TrashEntry): ToolResult {
        val trashFile = File(root, entry.trashPath)
        if (!trashFile.exists()) {
            return ToolResult(false, "Файл корзины отсутствует: «${entry.trashPath}».")
        }
        val original = File(root, entry.originalPath)
        if (original.exists()) {
            return ToolResult(
                false,
                "Исходный путь уже существует: «${entry.originalPath}». " +
                    "Восстановление отменено, чтобы не перезаписать данные."
            )
        }
        original.parentFile?.mkdirs()
        if (!move(trashFile, original) || trashFile.exists() || !original.exists()) {
            return ToolResult(false, "Не удалось восстановить: «${entry.originalPath}».")
        }
        trashRepository.remove(entry.id)
        return ToolResult(true, "Восстановлено: «${entry.originalPath}».")
    }

    /** Local-time stamp for a trash entry. */
    private fun formatTime(epochMillis: Long): String =
        formatTimestamp(epochMillis, "yyyy-MM-dd HH:mm")

    private companion object {
        private const val TRASH_DIR_NAME = ".andharness_trash"
    }
}
