package com.andharness.ru.data.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelSftp.LsEntry
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import java.io.File
import java.io.FileOutputStream

/**
 * SFTP concern of the SSH engine: `list`/`get`/`put` over one bounded SFTP channel.
 *
 * `get` writes atomically (temp `.<name>.part` + rename) so a half-downloaded file is never mistaken
 * for a complete one; `put` refuses to overwrite unless explicitly allowed; transfers are size-capped.
 */
internal class SshSftpRunner {

    /** Lists [path] (the login directory when blank). Bounded by [limit] entries. */
    fun list(session: Session, path: String, limit: Int, timeoutSec: Int): SftpListOutcome =
        withSftp(session, timeoutSec) { sftp ->
            val target = path.trim().ifEmpty { "." }
            val raw: List<LsEntry> = sftp.ls(target).toList()
            val entries = raw.mapNotNull { entry ->
                val name = entry.filename
                if (name == "." || name == "..") {
                    null
                } else {
                    SftpEntry(
                        name = name,
                        directory = entry.attrs.isDir,
                        sizeBytes = entry.attrs.size,
                        permissions = entry.attrs.permissionsString
                    )
                }
            }
            val displayed = entries.take(limit.coerceAtLeast(1))
            SftpListOutcome(
                path = runCatching { sftp.realpath(target) }.getOrDefault(target),
                entries = displayed,
                truncated = entries.size > displayed.size
            )
        }

    /**
     * Downloads [remote] into [destination] **atomically**: the bytes go to `.<name>.part` in the
     * destination directory and the file only appears under its real name after a successful
     * transfer and rename, so a half-downloaded file is never mistaken for a complete one.
     */
    fun get(session: Session, remote: String, destination: File, timeoutSec: Int): SftpGetOutcome {
        val parent = destination.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            runCatching { session.disconnect() }
            throw SshFailure(
                "Не удалось создать каталог для загрузки: ${parent.absolutePath}",
                SshFailure.Kind.LOCAL_IO
            )
        }
        val temp = File(parent, ".${destination.name}.part")
        try {
            val size = withSftp(session, timeoutSec) { sftp ->
                val remoteSize = runCatching { sftp.lstat(remote) }.getOrNull()?.size ?: -1L
                if (remoteSize > MAX_TRANSFER_BYTES) {
                    throw SshFailure(
                        "Файл $remote слишком большой для загрузки (${remoteSize / (1024 * 1024)} МБ, " +
                            "лимит ${MAX_TRANSFER_BYTES / (1024 * 1024)} МБ).",
                        SshFailure.Kind.REFUSED
                    )
                }
                FileOutputStream(temp).use { out -> sftp.get(remote, out) }
                temp.length()
            }
            if (!temp.renameTo(destination)) {
                if (destination.exists() && destination.isFile && !destination.delete()) {
                    throw SshFailure(
                        "Не удалось заменить ${destination.absolutePath} (файл занят).",
                        SshFailure.Kind.LOCAL_IO
                    )
                }
                if (!temp.renameTo(destination)) {
                    throw SshFailure(
                        "Не удалось атомарно записать ${destination.absolutePath}.",
                        SshFailure.Kind.LOCAL_IO
                    )
                }
            }
            return SftpGetOutcome(remote = remote, localPath = destination.absolutePath, sizeBytes = size)
        } catch (e: SshFailure) {
            runCatching { if (temp.exists()) temp.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { if (temp.exists()) temp.delete() }
            throw e
        }
    }

    /**
     * Uploads [local] to [remote]. Refuses to overwrite an existing remote file unless [overwrite] is
     * explicitly `true` (update.md §5).
     */
    fun put(
        session: Session,
        local: File,
        remote: String,
        overwrite: Boolean,
        timeoutSec: Int
    ): SftpPutOutcome = withSftp(session, timeoutSec) { sftp ->
        if (remoteExists(sftp, remote) && !overwrite) {
            throw SshFailure(
                "Файл $remote уже существует на сервере. Ничего не изменено — передай " +
                    "overwrite=true, если его действительно нужно перезаписать.",
                SshFailure.Kind.REFUSED
            )
        }
        java.io.FileInputStream(local).use { input ->
            sftp.put(input, remote, ChannelSftp.OVERWRITE)
        }
        SftpPutOutcome(localPath = local.absolutePath, remote = remote, sizeBytes = local.length())
    }

    private fun <T> withSftp(session: Session, timeoutSec: Int, block: (ChannelSftp) -> T): T {
        val channel = session.openChannel("sftp") as ChannelSftp
        try {
            channel.connect((timeoutSec * 1000).coerceIn(MIN_CONNECT_TIMEOUT_MS, MAX_CONNECT_TIMEOUT_MS))
        } catch (e: Exception) {
            runCatching { channel.disconnect() }
            throw SshFailure("Не удалось открыть SFTP-канал: ${e.message}", SshFailure.Kind.CONNECTION)
        }
        return try {
            block(channel)
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    /** `true` when [remote] exists; a "no such file" answer is `false`, anything else propagates. */
    private fun remoteExists(sftp: ChannelSftp, remote: String): Boolean = try {
        sftp.lstat(remote)
        true
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
    }

    private companion object {
        const val MIN_CONNECT_TIMEOUT_MS = 5_000
        const val MAX_CONNECT_TIMEOUT_MS = 30_000

        const val MAX_TRANSFER_BYTES = 128L * 1024 * 1024
    }
}
