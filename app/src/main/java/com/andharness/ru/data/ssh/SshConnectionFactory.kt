package com.andharness.ru.data.ssh

import android.content.Context
import android.net.Uri
import com.andharness.ru.domain.ssh.SshAuthType
import com.andharness.ru.domain.ssh.SshServer
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

/**
 * Connection/session concern of the SSH engine: opens one bounded [Session] per call, applies the
 * stored credentials and verifies the host key.
 *
 * Design rules that come straight from update.md §5.4:
 * - **Host keys are verified** through a custom [StoredHostKeyRepository] before authentication, so
 *   credentials are never offered to an unverified host. An unknown key refuses the connection and
 *   reports the fingerprint; a *changed* key always refuses; a key is only remembered when the
 *   caller explicitly asked to trust it (never silently).
 * - **Nothing is interactive.** No `UserInfo` is installed and no PTY is requested, so a command
 *   that wants a password prompt (`sudo`) fails instead of hanging.
 * - **Everything is bounded.** The socket timeout, the channel-connect timeout and the exec deadline
 *   are all finite, so a stalled server cannot hang the app.
 * - One short-lived session per call — no connection pool, no long-lived credentials in memory.
 *
 * https://github.com/mwiede/jsch
 */
internal class SshConnectionFactory(
    private val context: Context,
    private val secretStore: SshSecretStore,
    private val knownHostsStore: KnownHostsStore,
) {

    fun clampTimeout(timeoutSec: Int): Int = timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)

    fun open(server: SshServer, timeoutSec: Int, trustHost: Boolean): Session {
        val jsch = JSch()
        val repository = StoredHostKeyRepository(knownHostsStore, server.host, server.port, trustHost)
        jsch.hostKeyRepository = repository
        val session = try {
            jsch.getSession(server.user, server.host, server.port)
        } catch (e: JSchException) {
            throw SshFailure(
                "Не удалось создать SSH-сессию для ${server.endpoint}: ${e.message}",
                SshFailure.Kind.CONNECTION
            )
        }
        session.hostKeyRepository = repository
        // Verification is OURS (the repository above); JSch must not fall back to a prompt or to
        // "just accept it".
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        try {
            applyCredentials(jsch, session, server)
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw e
        }
        val connectTimeoutMs = (timeoutSec * 1000).coerceIn(MIN_CONNECT_TIMEOUT_MS, MAX_CONNECT_TIMEOUT_MS)
        try {
            session.timeout = connectTimeoutMs
            session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS)
            session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)
            session.connect(connectTimeoutMs)
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw translate(e, repository, server)
        }
        return session
    }

    private fun applyCredentials(jsch: JSch, session: Session, server: SshServer) {
        when (server.authType) {
            SshAuthType.PASSWORD -> {
                val password = secretStore.password(server.id)
                if (password.isNullOrEmpty()) {
                    throw SshFailure(
                        "Для сервера «${server.name}» не сохранён пароль — добавьте сервер заново " +
                            "с параметром password.",
                        SshFailure.Kind.SECRET_MISSING
                    )
                }
                session.setPassword(password)
            }

            SshAuthType.KEY -> {
                val keyBytes = readPrivateKey(server)
                val passphrase = secretStore.passphrase(server.id)?.toByteArray(Charsets.UTF_8)
                try {
                    jsch.addIdentity("andharness-${server.id}", keyBytes, null, passphrase)
                } catch (e: JSchException) {
                    throw SshFailure(
                        "Не удалось прочитать приватный ключ сервера «${server.name}»" +
                            (if (passphrase != null) " (проверьте парольную фразу)" else "") +
                            ": ${e.message}",
                        SshFailure.Kind.SECRET_MISSING
                    )
                } finally {
                    keyBytes.fill(0)
                }
            }
        }
    }

    /** Reads the private key from the SAF URI in `keyRef` — the key is never persisted by this app. */
    private fun readPrivateKey(server: SshServer): ByteArray {
        val ref = server.keyRef
        if (ref.isNullOrBlank()) {
            throw SshFailure(
                "У сервера «${server.name}» не указан key_ref — SAF-URI приватного ключа.",
                SshFailure.Kind.SECRET_MISSING
            )
        }
        val stream = try {
            context.contentResolver.openInputStream(Uri.parse(ref))
        } catch (e: Exception) {
            throw SshFailure(
                "Не удалось открыть приватный ключ по key_ref «$ref»: ${e.message}. " +
                    "Скорее всего, доступ к документу потерян — выдайте его заново.",
                SshFailure.Kind.SECRET_MISSING
            )
        } ?: throw SshFailure(
            "Не удалось открыть приватный ключ по key_ref «$ref» (null). Проверьте, что доступ к " +
                "документу всё ещё выдан.",
            SshFailure.Kind.SECRET_MISSING
        )
        return stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                if (total > MAX_KEY_BYTES) {
                    throw SshFailure(
                        "Файл по key_ref больше ${MAX_KEY_BYTES / 1024} КБ — это не похоже на " +
                            "приватный ключ.",
                        SshFailure.Kind.SECRET_MISSING
                    )
                }
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }
    }

    private fun translate(
        e: Exception,
        repository: StoredHostKeyRepository,
        server: SshServer
    ): SshFailure {
        val info = repository.observedKey
        when (repository.verdict) {
            HostKeyVerdict.CHANGED -> return SshFailure(
                "Ключ хоста ${server.host}:${server.port} ИЗМЕНИЛСЯ по сравнению с сохранённым — " +
                    "подключение отклонено (это признак подмены). Новый отпечаток: " +
                    "${info?.fingerprint ?: "неизвестен"} (${info?.keyType ?: "?"}). Если вы точно " +
                    "переустановили сервер, удалите и добавьте его заново, чтобы очистить доверие.",
                SshFailure.Kind.HOST_KEY_CHANGED,
                info
            )

            HostKeyVerdict.UNKNOWN -> return SshFailure(
                "Хост ${server.host}:${server.port} ещё не подтверждён. Отпечаток ключа: " +
                    "${info?.fingerprint ?: "неизвестен"} (${info?.keyType ?: "?"}). Сверьте его с " +
                    "сервером (ssh-keygen -lf) и повторите с trust_host=true, чтобы запомнить ключ.",
                SshFailure.Kind.HOST_KEY_UNKNOWN,
                info
            )

            HostKeyVerdict.MATCH, null -> Unit
        }
        val message = e.message.orEmpty()
        val kind = when {
            message.contains("Auth fail", ignoreCase = true) ||
                message.contains("Auth cancel", ignoreCase = true) ||
                message.contains("USERAUTH", ignoreCase = true) ||
                message.contains("auth fail", ignoreCase = true) -> SshFailure.Kind.AUTH

            e is java.net.SocketTimeoutException ||
                message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) -> SshFailure.Kind.TIMEOUT

            else -> SshFailure.Kind.CONNECTION
        }
        return SshFailure(
            "Не удалось подключиться к ${server.endpoint}: ${message.ifBlank { e.javaClass.simpleName }}",
            kind
        )
    }

    private companion object {
        const val MIN_TIMEOUT_SEC = 1
        const val MAX_TIMEOUT_SEC = 300

        const val MIN_CONNECT_TIMEOUT_MS = 5_000
        const val MAX_CONNECT_TIMEOUT_MS = 30_000

        const val SERVER_ALIVE_INTERVAL_MS = 15_000
        const val SERVER_ALIVE_COUNT_MAX = 3

        const val MAX_KEY_BYTES = 1024 * 1024
    }
}
