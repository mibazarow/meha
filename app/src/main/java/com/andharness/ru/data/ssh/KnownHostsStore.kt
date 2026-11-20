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
 * The known-hosts store of the SSH client (update.md §5.2/§5.4).
 *
 * It maps `host:port` to the server's public key (algorithm + base64, see [HostKeyFormat]) so a
 * connection can be refused when the key is unknown or has changed. It is kept in its own
 * **EncryptedSharedPreferences** file (Android-Keystore backed, own master-key alias) rather than a
 * plain file or a Room table: it is network-facing trust material, and the update.md decision was "a
 * separate protected store/file".
 *
 * Verification policy (the never-silently-trust rule) is the pure [KnownHostsPolicy]; this class only
 * persists what that policy decides.
 *
 * References:
 * - EncryptedSharedPreferences — https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences
 * - Android Keystore — https://developer.android.com/privacy-and-security/keystore
 */
@Suppress("DEPRECATION")
@Singleton
class KnownHostsStore @Inject constructor(
    @ApplicationContext context: Context
) : HostKeyTrustStore {

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

    /** The remembered value for `host:port`, or `null` when the host has never been trusted. */
    fun stored(host: String, port: Int): String? =
        prefs.getString(KnownHostsPolicy.endpointKey(host, port), null)

    /** The pure verdict for the key a server just presented. */
    override fun verdict(host: String, port: Int, info: HostKeyFormat.Info): HostKeyVerdict =
        KnownHostsPolicy.decide(stored(host, port), info)

    /** Remembers [info] as the trusted key of `host:port`. Only called after an explicit confirm. */
    override fun trust(host: String, port: Int, info: HostKeyFormat.Info) {
        prefs.edit().putString(KnownHostsPolicy.endpointKey(host, port), info.storedValue).apply()
    }

    /** Drops the entry for `host:port` (used when a server is removed from the registry). */
    fun forget(host: String, port: Int) {
        prefs.edit().remove(KnownHostsPolicy.endpointKey(host, port)).apply()
    }

    private companion object {
        const val PREFS_FILE = "andharness_ssh_known_hosts"
        const val MASTER_KEY_ALIAS = "andharness_ssh_known_hosts_key"
    }
}
