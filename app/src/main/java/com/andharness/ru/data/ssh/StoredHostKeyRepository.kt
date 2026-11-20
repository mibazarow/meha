package com.andharness.ru.data.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

/**
 * The host-key check of one connection attempt.
 *
 * [check] is called by JSch during key exchange, **before** any credential is sent, so returning a
 * non-OK result is what prevents us from talking to an unverified host. Behaviour:
 * - remembered and equal → [HostKeyRepository.OK];
 * - remembered and different → [HostKeyRepository.CHANGED] (always refused);
 * - not remembered → refused, unless this attempt explicitly asked to trust it, in which case the
 *   key is persisted first.
 *
 * **Every JSch callback parameter is nullable.** mwiede/jsch 0.2.20 calls `getHostKey(chost, null)`
 * from `Session.send_kexinit` — while proposing `server_host_key` algorithms, *before* key exchange
 * and authentication — and a Kotlin override declaring a non-null `type` inserts a null-check that
 * throws there, which made every SSH connection (host-key verification, `ssh_exec`, SFTP)
 * impossible. Declaring the platform types nullable and answering "no known keys" is both correct
 * (this repository memorises nothing; [check] alone decides) and crash-free.
 */
internal class StoredHostKeyRepository(
    private val store: HostKeyTrustStore,
    private val host: String,
    private val port: Int,
    private val trustUnknown: Boolean
) : HostKeyRepository {

    /** The key the server presented during this attempt (null when `check` was never reached). */
    var observedKey: HostKeyFormat.Info? = null
        private set

    /** What the store said about [observedKey]. */
    var verdict: HostKeyVerdict? = null
        private set

    override fun check(host: String?, key: ByteArray?): Int {
        val info = HostKeyFormat.describe(key) ?: return HostKeyRepository.NOT_INCLUDED
        observedKey = info
        val decision = store.verdict(this.host, port, info)
        verdict = decision
        return when (decision) {
            HostKeyVerdict.MATCH -> HostKeyRepository.OK
            HostKeyVerdict.CHANGED -> HostKeyRepository.CHANGED
            HostKeyVerdict.UNKNOWN -> if (trustUnknown) {
                store.trust(this.host, port, info)
                HostKeyRepository.OK
            } else {
                HostKeyRepository.NOT_INCLUDED
            }
        }
    }

    /** Persisting is done in [check] (only under an explicit trust); nothing to add here. */
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

    override fun remove(host: String?, type: String?) = Unit

    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = "andharness-known-hosts"

    /** JSch wants the keys it has memorised for a host; this repository keeps none, so it says "none". */
    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
