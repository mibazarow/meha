package com.andharness.ru.data.tools.files

import java.io.File

/**
 * Best-effort `.bak` backup support for `files_write`/`files_copy`: an overwrite stays recoverable
 * while the previous version is moved aside, and a failed operation can restore it.
 */
internal class FileBackups {

    /** The `.bak` backup path used by `files_write`/`files_copy` for [file]. */
    fun backupFile(file: File): File = File(file.parentFile, "${file.name}.bak")

    /**
     * Restores the previous version of [file] from the `.bak` backup made by `files_write` or
     * `files_copy`. Any leftover replacement at [file] is removed first (`deleteRecursively`
     * covers a partially-copied directory), then the rename consumes the backup, so a successful
     * restore leaves no `.bak` behind.
     */
    fun restoreBackup(file: File): Boolean {
        val backup = backupFile(file)
        if (!backup.exists()) return false
        file.deleteRecursively()
        return backup.renameTo(file)
    }

    /**
     * Removes the `.bak` backup made by `files_write`/`files_copy` once the write/copy was
     * verified. Returns a note for the success message, or `null` when this call left no backup.
     * A failed deletion is reported honestly instead of failing an operation that actually
     * succeeded.
     */
    fun cleanupBackup(file: File): String? {
        val backup = backupFile(file)
        if (!backup.exists()) return null
        return if (backup.deleteRecursively()) {
            "Резервная копия удалена после проверки."
        } else {
            "Запись выполнена, но резервную копию «${backup.name}» удалить не удалось."
        }
    }
}
