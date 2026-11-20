package com.andharness.ru.data.mcp

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
 * The secret half of the MCP registry.
 *
 * The optional custom auth header VALUE (a bearer token / API key) is kept here, in an
 * [EncryptedSharedPreferences] file whose master key lives in the **Android Keystore** — never in
 * the JSON [McpServerStore] writes, never in a log, never in a tool result. The header NAME is not
 * sensitive and stays in [com.andharness.ru.domain.mcp.McpServer].
 *
 * Secrets are keyed by the server's id, so removing a server removes its token with it
 * (`mcp_server_remove` calls [forget]).
 *
 * The Android-free parts of MCP (store, JSON-RPC codec, SSE parser, tool-name rules) are unit
 * tested on the JVM; this thin Android wrapper mirrors `SshSecretStore` and is not.
 *
 * References:
 * - EncryptedSharedPreferences — https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences
 * - Android Keystore — https://developer.android.com/privacy-and-security/keystore
 */
@Suppress("DEPRECATION")
@Singleton
class McpSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val masterKey: MasterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        PrefKeyEncryptionScheme.AES256_SIV,
        PrefValueEncryptionScheme.AES256_GCM,
    )

    /** The stored auth header value of a server, or `null` when none is stored. */
    fun authHeaderValue(serverId: String): String? = prefs.getString(valueKey(serverId), null)

    /** Stores (or, with `null`/blank, clears) the auth header value of a server. */
    fun setAuthHeaderValue(serverId: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(valueKey(serverId)).apply()
        } else {
            prefs.edit().putString(valueKey(serverId), value).apply()
        }
    }

    /** Drops a removed server's secret. */
    fun forget(serverId: String) {
        prefs.edit().remove(valueKey(serverId)).apply()
    }

    /** `true` when a value is on file — used for honest status text, never to print the secret. */
    fun hasAuthHeaderValue(serverId: String): Boolean = !authHeaderValue(serverId).isNullOrEmpty()

    private fun valueKey(serverId: String): String = "mcp_auth_$serverId"

    private companion object {
        const val PREFS_FILE = "andharness_mcp_secrets"
        const val MASTER_KEY_ALIAS = "andharness_mcp_secrets_key"
    }
}
