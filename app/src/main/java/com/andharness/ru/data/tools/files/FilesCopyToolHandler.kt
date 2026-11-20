package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** `files_copy`: copies a file or directory, with an optional recoverable overwrite. */
internal class FilesCopyToolHandler(
    private val io: FileToolIo,
    private val backups: FileBackups,
) : ToolHandler {
    override val name = "files_copy"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        var destination: File? = null
        var backupOwned = false
        return try {
            val overwrite =
                (arguments["overwrite"] as? JsonPrimitive)?.content?.toBoolean() ?: false
            // Both ends go through path(), which resolves them against every allowed root
            // and rejects `..` and any canonical result escaping those roots (A1).
            val source = File(io.path(arguments, "source"))
            val target = File(io.path(arguments, "destination"))
            destination = target

            if (!source.exists()) {
                return ToolResult(
                    false,
                    "Нечего копировать: «${source.absolutePath}» не существует."
                )
            }
            // No silent overwrite (A10): an existing destination is an honest error
            // unless the caller explicitly opted in via overwrite=true.
            if (target.exists() && !overwrite) {
                return ToolResult(
                    false,
                    "Назначение уже существует: «${target.absolutePath}». " +
                        "Копирование отменено (передайте overwrite=true для перезаписи)."
                )
            }

            // Kotlin stdlib File.copyTo / File.copyRecursively —
            // https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io/
            target.parentFile?.mkdirs()

            // Best-effort backup of the previous destination so an overwrite stays
            // recoverable. A same-parent rename stays on one filesystem, so it is atomic.
            // For a directory copy this moves the whole tree aside (`<name>.bak`), which is
            // also a directory-level rename; the limitation is that restoring is
            // best-effort: the partial copy is removed and the previous tree renamed back,
            // which cannot roll back a copyRecursively that failed midway through an
            // otherwise-sound destination — it simply restores the pre-copy tree.
            // A pre-existing `.bak` that THIS call did not create is never touched: if the
            // slot is already occupied we skip the backup rather than clobber it.
            if (target.exists() && overwrite) {
                val backup = backups.backupFile(target)
                if (!backup.exists()) {
                    runCatching { target.renameTo(backup) }
                    backupOwned = backup.exists()
                }
            }

            val copied = try {
                if (source.isDirectory) {
                    source.copyRecursively(target, overwrite = overwrite)
                } else {
                    source.copyTo(target, overwrite = overwrite)
                    true
                }
            } catch (e: Exception) {
                false
            }
            if (!copied) {
                val restored = if (backupOwned) backups.restoreBackup(target) else false
                backupOwned = false
                val suffix =
                    if (restored) " Исходный файл восстановлен из резервной копии." else ""
                return ToolResult(
                    false,
                    "Не удалось скопировать: ${source.absolutePath} → ${target.absolutePath}.$suffix"
                )
            }

            // Post-verify the result on disk before reporting success: a file must match
            // the source length, a directory must exist with the same recursive file count.
            val verified = if (source.isDirectory) {
                target.isDirectory && io.countFiles(target) == io.countFiles(source)
            } else {
                target.isFile && target.length() == source.length()
            }
            if (!verified) {
                val restored = if (backupOwned) backups.restoreBackup(target) else false
                backupOwned = false
                val suffix =
                    if (restored) " Исходный файл восстановлен из резервной копии." else ""
                return ToolResult(
                    false,
                    "Копирование не подтверждено: " +
                        "${source.absolutePath} → ${target.absolutePath}. Проверьте вручную.$suffix"
                )
            }

            // Verified overwrite: drop the backup this call made (a deletion failure is
            // reported honestly and never turns a successful copy into an error).
            val backupNote = if (backupOwned) backups.cleanupBackup(target) else null
            backupOwned = false
            ToolResult(
                true,
                "Скопировано: ${target.absolutePath}" +
                    (if (backupNote != null) " $backupNote" else "")
            )
        } catch (e: Exception) {
            val dest = destination
            val restored = if (backupOwned && dest != null) backups.restoreBackup(dest) else false
            backupOwned = false
            val suffix = if (restored) " Исходный файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка: ${e.message}$suffix")
        }
    }
}
