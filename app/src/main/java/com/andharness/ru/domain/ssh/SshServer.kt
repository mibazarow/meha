package com.andharness.ru.domain.ssh

/**
 * A registered SSH server (update.md §5.2).
 *
 * [keyRef] is a Storage Access Framework URI for [SshAuthType.KEY]; the private key itself is never
 * copied into this model, into Room or into the encrypted preferences — it is read from that URI at
 * connect time. Passwords/passphrases live in [com.andharness.ru.data.ssh.SshSecretStore].
 */
data class SshServer(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val authType: SshAuthType,
    val keyRef: String?,
    val createdAt: Long
) {
    /** `user@host:port` — the honest one-line identification of a server (never a secret). */
    val endpoint: String get() = "$user@$host:$port"
}
