package com.andharness.ru.domain.security


/**
 * Persistence for the *sealed* database passphrase. The value handed to [writeWrapped] must already
 * be sealed by a [ByteSealer]; nothing in this interface ever sees the raw passphrase bytes written
 * to disk. Keeping it abstract makes [DatabasePassphrase] testable on the JVM with an in-memory map.
 */
interface PassphraseStore {
    fun readWrapped(): String?
    fun writeWrapped(value: String)
    fun clear()
}
