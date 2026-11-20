package com.andharness.ru.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Pins [HostKeyFormat] — the parsing of the host-key blob JSch hands to a `HostKeyRepository`, which
 * is what the known-hosts store persists and what the user is shown as a fingerprint.
 */
class HostKeyFormatTest {

    @Test
    fun parsesAlgorithmNameAndKeepsTheWholeBlobAsBase64() {
        val blob = keyBlob("ssh-ed25519", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val info = HostKeyFormat.describe(blob)!!

        assertEquals("ssh-ed25519", info.keyType)
        assertEquals(Base64.getEncoder().encodeToString(blob), info.base64)
        assertEquals("ssh-ed25519 ${info.base64}", info.storedValue)
    }

    @Test
    fun fingerprintIsSha256OfTheWholeBlobInOpenSshNotation() {
        val blob = keyBlob("ssh-rsa", ByteArray(32) { it.toByte() })
        val expected = "SHA256:" + Base64.getEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
            .trimEnd('=')

        assertEquals(expected, HostKeyFormat.describe(blob)!!.fingerprint)
        assertTrue("OpenSSH prints SHA256:…", HostKeyFormat.fingerprint(blob).startsWith("SHA256:"))
        assertTrue(
            "base64 padding must be stripped",
            !HostKeyFormat.fingerprint(blob).contains('=')
        )
    }

    @Test
    fun differentKeysProduceDifferentFingerprints() {
        val first = HostKeyFormat.describe(keyBlob("ssh-ed25519", byteArrayOf(1)))!!
        val second = HostKeyFormat.describe(keyBlob("ssh-ed25519", byteArrayOf(2)))!!
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertNotEquals(first.storedValue, second.storedValue)
    }

    @Test
    fun malformedBlobsAreRejectedInsteadOfGuessed() {
        assertNull("null blob", HostKeyFormat.describe(null))
        assertNull("empty blob", HostKeyFormat.describe(ByteArray(0)))
        assertNull("truncated header", HostKeyFormat.describe(byteArrayOf(0, 0, 0, 8)))
        // Declares a 100-byte algorithm name but carries only 4 bytes of it.
        assertNull(
            "length runs past the end",
            HostKeyFormat.describe(byteArrayOf(0, 0, 0, 100, 1, 2, 3, 4))
        )
        // A "name" that is not printable ASCII cannot be an SSH algorithm name.
        assertNull(
            "non-printable algorithm name",
            HostKeyFormat.describe(byteArrayOf(0, 0, 0, 3, 1, 2, 3))
        )
    }

    private fun keyBlob(type: String, payload: ByteArray): ByteArray {
        val name = type.toByteArray(Charsets.US_ASCII)
        return ByteArrayOutputStream().apply {
            write(
                byteArrayOf(
                    (name.size ushr 24).toByte(),
                    (name.size ushr 16).toByte(),
                    (name.size ushr 8).toByte(),
                    name.size.toByte()
                )
            )
            write(name)
            write(payload)
        }.toByteArray()
    }
}
