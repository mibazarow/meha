package com.andharness.ru.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the host-key decision table of update.md §5.4: an unknown host is never trusted silently, and
 * a changed key is always refused.
 */
class KnownHostsPolicyTest {

    @Test
    fun noStoredEntryMeansUnknown() {
        assertEquals(HostKeyVerdict.UNKNOWN, KnownHostsPolicy.decide(null, "ssh-ed25519", "AAAA"))
        assertEquals(HostKeyVerdict.UNKNOWN, KnownHostsPolicy.decide("", "ssh-ed25519", "AAAA"))
        assertEquals(HostKeyVerdict.UNKNOWN, KnownHostsPolicy.decide("   ", "ssh-ed25519", "AAAA"))
    }

    @Test
    fun identicalKeyMatches() {
        assertEquals(
            HostKeyVerdict.MATCH,
            KnownHostsPolicy.decide("ssh-ed25519 AAAA", "ssh-ed25519", "AAAA")
        )
    }

    @Test
    fun differentKeyForTheSameHostIsChanged() {
        assertEquals(
            HostKeyVerdict.CHANGED,
            KnownHostsPolicy.decide("ssh-ed25519 AAAA", "ssh-ed25519", "BBBB")
        )
    }

    @Test
    fun sameKeyUnderADifferentAlgorithmIsChanged() {
        assertEquals(
            HostKeyVerdict.CHANGED,
            KnownHostsPolicy.decide("ssh-rsa AAAA", "ssh-ed25519", "AAAA")
        )
    }

    @Test
    fun comparisonIsExactAndNotFuzzy() {
        // A key that only differs in trailing characters, or in case, is a different key.
        assertEquals(
            HostKeyVerdict.CHANGED,
            KnownHostsPolicy.decide("ssh-ed25519 AAAAB", "ssh-ed25519", "AAAA")
        )
        assertEquals(
            HostKeyVerdict.CHANGED,
            KnownHostsPolicy.decide("ssh-ed25519 aaaa", "ssh-ed25519", "AAAA")
        )
    }

    @Test
    fun endpointKeyAlwaysCarriesThePort() {
        assertEquals("example.com:22", KnownHostsPolicy.endpointKey("example.com", 22))
        assertEquals("example.com:2222", KnownHostsPolicy.endpointKey(" example.com ", 2222))
    }

    @Test
    fun infoOverloadBehavesLikeTheRawOne() {
        val blob = byteArrayOf(0, 0, 0, 11) + "ssh-ed25519".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(1, 2, 3)
        val info = HostKeyFormat.describe(blob)!!
        assertEquals(HostKeyVerdict.UNKNOWN, KnownHostsPolicy.decide(null, info))
        assertEquals(HostKeyVerdict.MATCH, KnownHostsPolicy.decide(info.storedValue, info))
        assertEquals(HostKeyVerdict.CHANGED, KnownHostsPolicy.decide("ssh-rsa OTHER", info))
    }
}
