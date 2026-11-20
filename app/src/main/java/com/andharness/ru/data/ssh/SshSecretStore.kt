package com.andharness.ru.data.ssh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme
import androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The secret half of the SSH registry (update.md §5.2/§5.4).
 *
 * - A **password** (or a **key passphrase**) is kept here, in an [EncryptedSharedPreferences] file
 *   whose master key lives in the **Android Keystore** — never in Room, never in a log, never in a
 *   tool result.
 * - A **private key** is *not* kept anywhere: the registry stores only a Storage Access Framework
 *   URI (`ssh_servers.keyRef`) and the key bytes are read from that URI at connect time. A
 *   passphrase-protected key therefore also only stores its passphrase here, not the key.
 *
 * Secrets are keyed by the server's row id, so removing a server removes them with it.
 *
 * References:
 * - EncryptedSharedPreferences — https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences
 * - Android Keystore — https://developer.android.com/privacy-and-security/keystore
 */
@Suppress("DEPRECATION")
@Singleton
class SshSecretStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val masterKey: MasterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        PrefKeyEncryptionScheme.AES256_SIV,
        PrefValueEncryptionScheme.AES256_GCM
    )

    /** The stored password of a server, or `null` when none is stored. */
    fun password(serverId: Long): String? = prefs.getString(passwordKey(serverId), null)

    /** Stores (or, with `null`/blank, clears) the password of a server. */
    fun setPassword(serverId: Long, password: String?) {
        if (password.isNullOrEmpty()) {
            prefs.edit().remove(passwordKey(serverId)).apply()
        } else {
            prefs.edit().putString(passwordKey(serverId), password).apply()
        }
    }

    /** The stored passphrase of a server's private key, or `null` when the key is unprotected. */
    fun passphrase(serverId: Long): String? = prefs.getString(passphraseKey(serverId), null)

    /** Stores (or, with `null`/blank, clears) the passphrase of a server's private key. */
    fun setPassphrase(serverId: Long, passphrase: String?) {
        if (passphrase.isNullOrEmpty()) {
            prefs.edit().remove(passphraseKey(serverId)).apply()
        } else {
            prefs.edit().putString(passphraseKey(serverId), passphrase).apply()
        }
    }

    /** Drops every secret of a removed server. */
    fun forget(serverId: Long) {
        prefs.edit().remove(passwordKey(serverId)).remove(passphraseKey(serverId)).apply()
    }

    /** `true` when a password is on file — used for honest error messages, never to print it. */
    fun hasPassword(serverId: Long): Boolean = !password(serverId).isNullOrEmpty()

    private fun passwordKey(serverId: Long): String = "ssh_password_$serverId"

    private fun passphraseKey(serverId: Long): String = "ssh_key_passphrase_$serverId"

    private companion object {
        const val PREFS_FILE = "andharness_ssh_secrets"
        const val MASTER_KEY_ALIAS = "andharness_ssh_secrets_key"
    }
}
