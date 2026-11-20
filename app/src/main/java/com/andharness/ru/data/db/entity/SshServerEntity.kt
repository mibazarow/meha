package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A registered SSH server (update.md §5.2, table `ssh_servers`).
 *
 * **No secrets live here.** The password (or the key passphrase) is kept in the Android-Keystore
 * backed [com.andharness.ru.data.ssh.SshSecretStore]; a private key is never copied at all — only
 * the Storage Access Framework URI the user granted is referenced through [keyRef] and the key is
 * read from there at connect time.
 *
 * The unique index on [name] is what makes "server by name" an unambiguous lookup and is also what
 * `ssh_server_add` relies on to refuse a silent overwrite of an existing registration.
 */
@Entity(
    tableName = "ssh_servers",
    indices = [Index(value = ["name"], unique = true)]
)
data class SshServerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Human-readable, unique name used to address the server (e.g. "vps-prod"). */
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    /** `PASSWORD` or `KEY` (see [com.andharness.ru.domain.ssh.SshAuthType]). */
    val authType: String,
    /** SAF URI of the private key for `KEY` auth; `null` for password auth. Never the key itself. */
    val keyRef: String?,
    val createdAt: Long
)
