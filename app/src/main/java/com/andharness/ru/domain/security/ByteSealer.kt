package com.andharness.ru.domain.security

/**
 * Seals a small secret with a key that never leaves the device.
 *
 * The Room database passphrase (update.md idea #7) must be persisted between app launches but must
 * never be written to disk in the clear. On Android the key is a non-extractable AES key in the
 * **Android Keystore**; on the JVM (unit tests) it is an ordinary in-memory key.
 *
 * This interface keeps the Android-specific key handling thin so the surrounding logic
 * ([DatabasePassphrase]) is fully unit-testable without Android.
 *
 * Official references:
 * - Android Keystore — https://developer.android.com/privacy-and-security/keystore
 */
interface ByteSealer {

    /** Returns a self-contained sealed blob (the implementation frames whatever it needs, e.g. the IV). */
    fun seal(plaintext: ByteArray): ByteArray

    /**
     * Reverses [seal].
     *
     * @throws Exception when the blob is corrupt, was sealed with another key, or the key is gone
     *         (for example after a Keystore reset). Callers must treat this as "the passphrase is
     *         unrecoverable" and fail honestly — never guess a key.
     */
    fun unseal(sealed: ByteArray): ByteArray
}
