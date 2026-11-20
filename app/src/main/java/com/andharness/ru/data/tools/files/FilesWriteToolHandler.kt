package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream

/**
 * `files_write`: atomically writes a UTF-8 text file with a recoverable `.bak` backup.
 *
 * [pathResolver] resolves the required `path` argument to a canonical absolute path (in production
 * this is [FileToolIo.path], which containment-checks against the allowed storage roots); tests pass
 * a lambda over a temp dir so the write/append semantics can be verified without an Android Context.
 */
internal class FilesWriteToolHandler(
    private val pathResolver: (JsonObject, String) -> String,
    private val backups: FileBackups,
) : ToolHandler {
    override val name = "files_write"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        var target: File? = null
        var backupOwned = false
        return try {
        val file = File(pathResolver(arguments, "path"))
        target = file
        val content = (arguments["content"] as? JsonPrimitive)?.content ?: ""
        // BUG-050: reject a single payload over the documented size up front with an honest
        // "split into parts" error instead of letting it reach disk (it would otherwise exceed the
        // model's output-token budget and fail as malformed JSON on the way in).
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > FileToolIo.MAX_SINGLE_WRITE_BYTES) {
            return ToolResult(false, FileToolIo.TOO_LARGE_ONE_CALL_MESSAGE)
        }
        val overwrite = (arguments["overwrite"] as? JsonPrimitive)
            ?.content?.toBooleanStrictOrNull() ?: true
        // No silent clobber: an existing target with overwrite=false is an honest error
        // and the file is left untouched.
        if (file.exists() && !overwrite) {
            return ToolResult(
                false,
                "Файл уже существует: «${file.absolutePath}». Перезапись отключена (overwrite=false)."
            )
        }
        file.parentFile?.mkdirs()

        // Atomic replace: write the whole content into a temp file in the SAME directory,
        // flush it and force it to disk (fsync), then rename it over the target. A
        // same-volume rename is atomic, so an interrupted write can never leave a
        // half-written target behind.
        // java.io.FileOutputStream —
        // https://developer.android.com/reference/java/io/FileOutputStream
        // FileDescriptor.sync() —
        // https://developer.android.com/reference/java/io/FileDescriptor#sync()
        // File#renameTo —
        // https://developer.android.com/reference/java/io/File#renameTo(java.io.File)
        val temp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(temp).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
        } catch (e: Exception) {
            temp.delete()
            return ToolResult(false, "Не удалось записать временный файл: ${e.message}")
        }

        // Best-effort backup of the previous version so an overwrite stays recoverable.
        // The backup is removed again on success and restored on failure — but only when
        // THIS call actually created it, so a pre-existing `.bak` is never touched.
        if (file.exists()) {
            runCatching {
                val backup = backups.backupFile(file)
                backup.delete()
                file.renameTo(backup)
            }
            backupOwned = backups.backupFile(file).isFile
        }

        if (!temp.renameTo(file)) {
            temp.delete()
            val restored = if (backupOwned) backups.restoreBackup(file) else false
            backupOwned = false
            val suffix = if (restored) " Исходный файл восстановлен из резервной копии." else ""
            return ToolResult(
                false,
                "Не удалось атомарно заменить файл: «${file.absolutePath}».$suffix"
            )
        }

        // Post-write verification: the target must exist and hold exactly the UTF-8 bytes.
        val actual = if (file.exists()) file.length() else -1L
        if (actual != bytes.size.toLong()) {
            val restored = if (backupOwned) backups.restoreBackup(file) else false
            backupOwned = false
            val suffix = if (restored) " Исходный файл восстановлен из резервной копии." else ""
            return ToolResult(
                false,
                "Проверка после записи не пройдена: ожидалось ${bytes.size} байт, записано $actual.$suffix"
            )
        }
        // Verified overwrite: drop the backup this call made (a deletion failure is
        // reported honestly and never turns a successful write into an error).
        val backupNote = if (backupOwned) backups.cleanupBackup(file) else null
        backupOwned = false
        ToolResult(
            true,
            "Записано ${bytes.size} байт." + if (backupNote != null) " $backupNote" else ""
        )
        } catch (e: Exception) {
            val restored = if (backupOwned && target != null) backups.restoreBackup(target!!) else false
            val suffix = if (restored) " Исходный файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка: ${e.message}$suffix")
        }
    }
}
