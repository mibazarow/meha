package com.andharness.ru.data.ssh

/**
 * The pure decision table of host-key verification (update.md §5.4): a stored entry, the key a server
 * presented, and nothing else. Kept free of Android/JSch so the security-relevant behaviour — never
 * silently trust, never accept a changed key — is unit-tested directly.
 */
object KnownHostsPolicy {

    /** The known-hosts key of an endpoint: `host:port` (port is always part of the identity). */
    fun endpointKey(host: String, port: Int): String = "${host.trim()}:$port"

    /**
     * @param stored the value remembered for the endpoint ([HostKeyFormat.Info.storedValue]), or
     *   `null`/blank when the host is not known yet.
     * @return [HostKeyVerdict.MATCH] on an exact match, [HostKeyVerdict.UNKNOWN] when nothing is
     *   stored, [HostKeyVerdict.CHANGED] otherwise. Comparison is exact and case-sensitive: a key
     *   that merely *looks* similar is a changed key.
     */
    fun decide(stored: String?, keyType: String, keyBase64: String): HostKeyVerdict {
        val remembered = stored?.trim().orEmpty()
        if (remembered.isEmpty()) return HostKeyVerdict.UNKNOWN
        val presented = "$keyType $keyBase64"
        return if (remembered == presented) HostKeyVerdict.MATCH else HostKeyVerdict.CHANGED
    }

    /** Convenience overload for the objects produced by [HostKeyFormat]. */
    fun decide(stored: String?, info: HostKeyFormat.Info): HostKeyVerdict =
        decide(stored, info.keyType, info.base64)
}
