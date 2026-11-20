package com.andharness.ru.data.db.encryption

import android.content.SharedPreferences
import com.andharness.ru.domain.security.PassphraseStore

/**
 * [PassphraseStore] over an ordinary `SharedPreferences` file that holds **only the sealed
 * passphrase** (update.md idea #7). Sealing is done by [AndroidKeystoreByteSealer] before the value
 * reaches this class, so nothing readable is ever written here.
 *
 * `commit()` is used instead of `apply()` on purpose: the sealed passphrase must be durably on disk
 * *before* the encrypted database is created. If the process died after the database file was
 * written but before the preferences hit the disk, the passphrase would be lost and the database
 * unreadable.
 *
 * The prefs file is excluded from Auto Backup (see `res/xml/backup_rules.xml` and
 * `res/xml/data_extraction_rules.xml`), exactly like the other Keystore-backed stores: a restored
 * copy would be useless because the Keystore key does not travel with it.
 */
class SharedPrefsPassphraseStore(
    private val prefs: SharedPreferences
) : PassphraseStore {

    override fun readWrapped(): String? = prefs.getString(KEY_PASSPHRASE, null)

    override fun writeWrapped(value: String) {
        prefs.edit().putString(KEY_PASSPHRASE, value).commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY_PASSPHRASE).commit()
    }

    companion object {
        /** Separate preferences file so the sealed blob is easy to exclude from backups. */
        const val PREFS_FILE = "andharness_db_encryption"

        /** Storage key for the sealed passphrase; the `v1` suffix allows a future format change. */
        const val KEY_PASSPHRASE = "passphrase_wrapped_v1"
    }
}
