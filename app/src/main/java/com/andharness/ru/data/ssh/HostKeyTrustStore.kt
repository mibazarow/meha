package com.andharness.ru.data.ssh

/**
 * The trust decisions [StoredHostKeyRepository] needs, split out of [KnownHostsStore] so the
 * repository's JSch callbacks can be unit-tested on the JVM: [KnownHostsStore] itself needs an
 * Android `Context` (EncryptedSharedPreferences), while the JSch wiring — notably the null-safety of
 * `getHostKey(host, type)` — is pure logic and must be provable without a device.
 */
interface HostKeyTrustStore {

    /** The verdict for the key a server just presented. */
    fun verdict(host: String, port: Int, info: HostKeyFormat.Info): HostKeyVerdict

    /** Remembers [info] as the trusted key of `host:port`; only called after an explicit confirm. */
    fun trust(host: String, port: Int, info: HostKeyFormat.Info)
}
