package com.andharness.ru.domain.security

import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the SQLCipher passphrase provider (update.md idea #7). The Android Keystore sealer
 * is replaced by a deterministic-but-reversible fake, so the *storage* contract is what is tested:
 * a fresh random passphrase is created once, sealed before storage, and reused afterwards.
 */
class DatabasePassphraseTest {

    /** In-memory [PassphraseStore]. */
    private class FakeStore : PassphraseStore {
        var value: String? = null
        override fun readWrapped(): String? = value
        override fun writeWrapped(value: String) {
            this.value = value
        }

        override fun clear() {
            value = null
        }
    }

    /** Reversible "sealer" that XORs with 0x5A and prepends a 1-byte marker; never used on a device. */
    private class FakeSealer : ByteSealer {
        override fun seal(plaintext: ByteArray): ByteArray =
            byteArrayOf(1) + plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun unseal(sealed: ByteArray): ByteArray {
            require(sealed.isNotEmpty() && sealed[0] == 1.toByte()) { "bad seal" }
            return sealed.copyOfRange(1, sealed.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private class BoomSealer : ByteSealer {
        override fun seal(plaintext: ByteArray): ByteArray = plaintext
        override fun unseal(sealed: ByteArray): ByteArray = throw IllegalStateException("keystore lost")
    }

    private fun provider(store: FakeStore, sealer: ByteSealer = FakeSealer()): DatabasePassphrase =
        DatabasePassphrase(store, sealer, SecureRandom())

    @Test
    fun `first use creates a 32-byte passphrase and seals it`() {
        val store = FakeStore()
        val passphrase = provider(store).get()

        assertEquals(DatabasePassphrase.PASSPHRASE_BYTES, passphrase.size)
        val wrapped = requireNotNull(store.value)
        // The stored value must never contain the raw passphrase.
        assertFalse(wrapped.contains(Base64.getEncoder().encodeToString(passphrase)))
    }

    @Test
    fun `second use returns the same passphrase without regenerating`() {
        val store = FakeStore()
        val provider = provider(store)
        val first = provider.get()
        val firstWrapped = store.value
        val second = provider.get()

        assertArrayEquals(first, second)
        assertEquals(firstWrapped, store.value)
    }

    @Test
    fun `sealed bytes never equal the raw passphrase`() {
        val store = FakeStore()
        val provider = provider(store)
        val passphrase = provider.get()
        val sealed = Base64.getDecoder().decode(requireNotNull(store.value))
        assertFalse(sealed.contentEquals(passphrase))
    }

    @Test
    fun `clear then get creates a different passphrase`() {
        val store = FakeStore()
        val provider = provider(store)
        val first = provider.get()
        provider.clear()
        assertFalse(provider.exists())
        val second = provider.get()

        assertEquals(DatabasePassphrase.PASSPHRASE_BYTES, second.size)
        assertNotEquals(
            Base64.getEncoder().encodeToString(first),
            Base64.getEncoder().encodeToString(second)
        )
    }

    @Test
    fun `exists reflects whether a passphrase was ever created`() {
        val store = FakeStore()
        val provider = provider(store)
        assertFalse(provider.exists())
        provider.get()
        assertTrue(provider.exists())
    }

    @Test
    fun `unseal failure is propagated honestly instead of inventing a new passphrase`() {
        val store = FakeStore()
        val seeded = DatabasePassphrase(store, FakeSealer(), SecureRandom())
        seeded.get() // creates and seals a passphrase

        val lostKey = DatabasePassphrase(store, BoomSealer(), SecureRandom())
        assertThrows(IllegalStateException::class.java) { lostKey.get() }
    }

    @Test
    fun `two fresh databases get different passphrases`() {
        val a = provider(FakeStore()).get()
        val b = provider(FakeStore()).get()
        assertFalse(a.contentEquals(b))
    }
}
