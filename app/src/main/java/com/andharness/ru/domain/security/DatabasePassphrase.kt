package com.andharness.ru.domain.security

import java.security.SecureRandom
import java.util.Base64

/**
 * Supplies the SQLCipher passphrase for the personal Room database (update.md idea #7).
 *
 * A fresh random 32-byte passphrase is generated on first use and stored **only in sealed form**:
 * `Base64(sealer.seal(passphrase))` goes into the store, so the plaintext passphrase never touches
 * disk. On Android the sealing key lives in the Android Keystore and is not extractable; if that key
 * is lost (Keystore reset, restore on another device) [get] throws instead of silently inventing a
 * new passphrase — a new passphrase would make the existing encrypted database unreadable.
 *
 * Official references:
 * - `SecureRandom` — https://developer.android.com/reference/java/security/SecureRandom
 * - Android Keystore — https://developer.android.com/privacy-and-security/keystore
 */
class DatabasePassphrase(
    private val store: PassphraseStore,
    private val sealer: ByteSealer,
    private val random: SecureRandom = SecureRandom()
) {

    /** True once a passphrase has been created and sealed (the database may already be encrypted). */
    fun exists(): Boolean = store.readWrapped() != null

    /**
     * Returns the passphrase, creating and sealing a fresh random one on first use.
     *
     * @throws Exception when an existing sealed passphrase cannot be unsealed (key lost).
     */
    @Synchronized
    fun get(): ByteArray {
        store.readWrapped()?.let { wrapped ->
            val sealed = Base64.getDecoder().decode(wrapped)
            return sealer.unseal(sealed)
        }
        val fresh = ByteArray(PASSPHRASE_BYTES).also { random.nextBytes(it) }
        store.writeWrapped(Base64.getEncoder().encodeToString(sealer.seal(fresh)))
        return fresh
    }

    /** Forgets the sealed passphrase. The caller is responsible for what happens to the database. */
    @Synchronized
    fun clear() {
        store.clear()
    }

    companion object {
        /** 256-bit passphrase: the maximum useful key length for SQLCipher's default AES-256. */
        const val PASSPHRASE_BYTES: Int = 32
    }
}
