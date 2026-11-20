package com.andharness.ru.data.ssh

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the null-safety of the JSch [HostKeyRepository] callbacks.
 *
 * Regression guard for the bug that made every SSH connection fail: mwiede/jsch 0.2.20
 * `Session.send_kexinit` calls `getHostKey(chost, null)` (a literal `null` type) while proposing
 * `server_host_key` algorithms — **before** key exchange and authentication. A Kotlin override
 * declaring `type: String` (non-null) compiles in an intrinsic null-check, so that pre-auth call
 * threw an NPE and `ssh_exec`/SFTP/host-key verification were all unreachable.
 */
class StoredHostKeyRepositoryTest {

    private class FakeStore : HostKeyTrustStore {
        var nextVerdict: HostKeyVerdict = HostKeyVerdict.UNKNOWN
        var trustedInfo: HostKeyFormat.Info? = null

        override fun verdict(host: String, port: Int, info: HostKeyFormat.Info): HostKeyVerdict =
            nextVerdict

        override fun trust(host: String, port: Int, info: HostKeyFormat.Info) {
            trustedInfo = info
        }
    }

    private val store = FakeStore()

    private fun repository(trustUnknown: Boolean = false) =
        StoredHostKeyRepository(store, "example.com", 22, trustUnknown)

    private fun ed25519Blob(): ByteArray =
        byteArrayOf(0, 0, 0, 11) + "ssh-ed25519".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 2, 3)

    @Test
    fun getHostKeyToleratesNullHostAndType() {
        val repository = repository()

        // Exactly what Session.send_kexinit does (host, null), plus the fully-null shape.
        assertEquals(0, repository.getHostKey(null, null).size)
        assertEquals(0, repository.getHostKey("example.com", null).size)
        assertEquals(0, repository.getHostKey(null, "ssh-ed25519").size)
        assertEquals(0, repository.getHostKey().size)
    }

    @Test
    fun checkToleratesANullKeyWithoutRecordingAVerdict() {
        val repository = repository()

        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("example.com", null))
        assertNull(repository.observedKey)
        assertNull(repository.verdict)
    }

    @Test
    fun everyCallbackToleratesNullArguments() {
        val repository = repository()

        repository.add(null, null)
        repository.remove(null, null)
        repository.remove(null, null, null)
        assertEquals("andharness-known-hosts", repository.getKnownHostsRepositoryID())
    }

    @Test
    fun unknownKeyIsRefusedAndNotRememberedWithoutTrust() {
        val repository = repository(trustUnknown = false)
        val blob = ed25519Blob()

        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("example.com", blob))
        assertEquals(HostKeyFormat.describe(blob), repository.observedKey)
        assertEquals(HostKeyVerdict.UNKNOWN, repository.verdict)
        assertNull(store.trustedInfo)
    }

    @Test
    fun unknownKeyIsRememberedOnlyWhenTrustWasExplicitlyAsked() {
        val repository = repository(trustUnknown = true)
        val blob = ed25519Blob()

        assertEquals(HostKeyRepository.OK, repository.check("example.com", blob))
        assertEquals(HostKeyFormat.describe(blob), store.trustedInfo)
    }

    @Test
    fun changedKeyIsRefusedEvenWhenTrustWasAsked() {
        store.nextVerdict = HostKeyVerdict.CHANGED
        val repository = repository(trustUnknown = true)

        assertEquals(HostKeyRepository.CHANGED, repository.check("example.com", ed25519Blob()))
        assertEquals(HostKeyVerdict.CHANGED, repository.verdict)
        assertNull(store.trustedInfo)
    }
}
