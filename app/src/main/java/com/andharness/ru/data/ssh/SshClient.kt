package com.andharness.ru.data.ssh

import android.content.Context
import com.andharness.ru.domain.ssh.SshServer
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SSH engine (update.md §5): exec channels and SFTP over the maintained JSch fork
 * (https://github.com/mwiede/jsch).
 *
 * This class is the façade and the error-boundary: connection/session establishment lives in
 * [SshConnectionFactory], exec + stream drain in [SshExecRunner], and SFTP in [SshSftpRunner]. It
 * keeps the public `exec`/`list`/`get`/`put` API, validates inputs, wraps unexpected errors as
 * [SshFailure] and guarantees the one-session-per-call lifetime.
 *
 * Design rules that come straight from update.md §5.4:
 * - **Host keys are verified** through a custom [StoredHostKeyRepository] before authentication, so
 *   credentials are never offered to an unverified host. An unknown key refuses the connection and
 *   reports the fingerprint; a *changed* key always refuses; a key is only remembered when the caller
 *   explicitly asked to trust it (never silently).
 * - **Nothing is interactive.** No `UserInfo` is installed and no PTY is requested, so a command that
 *   wants a password prompt (`sudo`) fails instead of hanging.
 * - **Everything is bounded.** The socket timeout, the channel-connect timeout, the exec deadline and
 *   the transfer-size caps are all finite, so a stalled server cannot hang the app.
 * - One short-lived session per call — no connection pool, no long-lived credentials in memory.
 */
@Singleton
class SshClient @Inject constructor(
    @ApplicationContext context: Context,
    secretStore: SshSecretStore,
    knownHostsStore: KnownHostsStore
) {

    private val connection = SshConnectionFactory(context, secretStore, knownHostsStore)
    private val exec = SshExecRunner()
    private val files = SshSftpRunner()

    // ---- exec ----

    /**
     * Runs [command] and returns stdout, stderr, the exit status and whether it timed out.
     *
     * @param trustHost `true` remembers the presented key when this host is not known yet; a changed
     *   key is refused regardless.
     * @throws SshFailure
     */
    suspend fun exec(
        server: SshServer,
        command: String,
        timeoutSec: Int,
        trustHost: Boolean
    ): SshExecOutcome = withContext(Dispatchers.IO) {
        if (command.isBlank()) {
            throw SshFailure("Пустая команда — выполнять нечего.", SshFailure.Kind.REFUSED)
        }
        val cap = connection.clampTimeout(timeoutSec)
        val session = connection.open(server, cap, trustHost)
        try {
            exec.run(session, command, cap)
        } catch (e: SshFailure) {
            throw e
        } catch (e: Exception) {
            throw SshFailure(sftpErrorText(e, server), SshFailure.Kind.PROTOCOL)
        } finally {
            runCatching { session.disconnect() }
        }
    }

    // ---- SFTP ----

    /** Lists [path] (the login directory when blank). Bounded by [limit] entries. */
    suspend fun list(
        server: SshServer,
        path: String,
        limit: Int,
        timeoutSec: Int,
        trustHost: Boolean
    ): SftpListOutcome = withContext(Dispatchers.IO) {
        val cap = connection.clampTimeout(timeoutSec)
        val session = connection.open(server, cap, trustHost)
        try {
            files.list(session, path, limit, cap)
        } catch (e: SshFailure) {
            throw e
        } catch (e: Exception) {
            throw SshFailure(sftpErrorText(e, server), SshFailure.Kind.PROTOCOL)
        } finally {
            runCatching { session.disconnect() }
        }
    }

    /**
     * Downloads [remote] into [destination] **atomically**: the bytes go to `.<name>.part` in the
     * destination directory and the file only appears under its real name after a successful
     * transfer and rename, so a half-downloaded file is never mistaken for a complete one.
     */
    suspend fun get(
        server: SshServer,
        remote: String,
        destination: File,
        timeoutSec: Int,
        trustHost: Boolean
    ): SftpGetOutcome = withContext(Dispatchers.IO) {
        val cap = connection.clampTimeout(timeoutSec)
        val session = connection.open(server, cap, trustHost)
        try {
            files.get(session, remote, destination, cap)
        } catch (e: SshFailure) {
            throw e
        } catch (e: Exception) {
            throw SshFailure(sftpErrorText(e, server), SshFailure.Kind.PROTOCOL)
        } finally {
            runCatching { session.disconnect() }
        }
    }

    /**
     * Uploads [local] to [remote]. Refuses to overwrite an existing remote file unless [overwrite] is
     * explicitly `true` (update.md §5).
     */
    suspend fun put(
        server: SshServer,
        local: File,
        remote: String,
        overwrite: Boolean,
        timeoutSec: Int,
        trustHost: Boolean
    ): SftpPutOutcome = withContext(Dispatchers.IO) {
        if (!local.isFile || !local.canRead()) {
            throw SshFailure(
                "Локальный файл не найден или недоступен: ${local.absolutePath}",
                SshFailure.Kind.LOCAL_IO
            )
        }
        if (local.length() > MAX_TRANSFER_BYTES) {
            throw SshFailure(
                "Файл ${local.absolutePath} слишком большой для загрузки " +
                    "(${local.length() / (1024 * 1024)} МБ, лимит ${MAX_TRANSFER_BYTES / (1024 * 1024)} МБ).",
                SshFailure.Kind.REFUSED
            )
        }
        val cap = connection.clampTimeout(timeoutSec)
        val session = connection.open(server, cap, trustHost)
        try {
            files.put(session, local, remote, overwrite, cap)
        } catch (e: SshFailure) {
            throw e
        } catch (e: Exception) {
            throw SshFailure(sftpErrorText(e, server), SshFailure.Kind.PROTOCOL)
        } finally {
            runCatching { session.disconnect() }
        }
    }

    private fun sftpErrorText(e: Exception, server: SshServer): String = when (e) {
        is SftpException -> "SFTP-ошибка на ${server.endpoint}: ${e.message} (код ${e.id})"
        is JSchException -> "Ошибка SSH на ${server.endpoint}: ${e.message}"
        is IOException -> "Ошибка ввода-вывода на ${server.endpoint}: ${e.message}"
        else -> "Ошибка на ${server.endpoint}: ${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        const val MAX_TRANSFER_BYTES = 128L * 1024 * 1024
    }
}
