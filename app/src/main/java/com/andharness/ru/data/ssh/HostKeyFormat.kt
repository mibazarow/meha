package com.andharness.ru.data.ssh

import java.security.MessageDigest
import java.util.Base64

/**
 * Turns a raw SSH host-key blob (the `byte[]` JSch hands to a
 * [com.jcraft.jsch.HostKeyRepository]) into the three things this app stores and shows:
 * the key algorithm, the base64 of the whole blob (what OpenSSH calls the "key"), and the
 * `SHA256:…` fingerprint a user can compare against `ssh-keygen -lf` output.
 *
 * Pure JVM (only [MessageDigest] and [java.util.Base64], both available on Android API 26+), so it
 * is unit-tested on the JVM. Reference: RFC 4253 §6.6 — a public key is an SSH string holding the
 * algorithm name followed by the algorithm-specific key data
 * (https://datatracker.ietf.org/doc/html/rfc4253#section-6.6);
 * fingerprint format per `ssh-keygen(1)` / OpenSSH `SHA256` base64 without padding.
 */
object HostKeyFormat {

    /** A parsed host key: never a secret — public key material only. */
    data class Info(
        /** e.g. `ssh-ed25519`, `ssh-rsa`, `ecdsa-sha2-nistp256`. */
        val keyType: String,
        /** Base64 of the whole key blob (the OpenSSH "key" column). */
        val base64: String,
        /** `SHA256:<base64-no-padding>` of the blob, as `ssh-keygen -lf` prints it. */
        val fingerprint: String
    ) {
        /** The exact string stored in the known-hosts store for comparison. */
        val storedValue: String get() = "$keyType $base64"
    }

    /**
     * Parses [blob] into an [Info], or returns `null` when it is not a well-formed SSH public key
     * (too short, or the declared algorithm-name length runs past the end of the blob).
     */
    fun describe(blob: ByteArray?): Info? {
        if (blob == null || blob.size < 5) return null
        val nameLength = ((blob[0].toInt() and 0xFF) shl 24) or
            ((blob[1].toInt() and 0xFF) shl 16) or
            ((blob[2].toInt() and 0xFF) shl 8) or
            (blob[3].toInt() and 0xFF)
        if (nameLength <= 0 || 4 + nameLength > blob.size) return null
        val keyType = String(blob, 4, nameLength, Charsets.US_ASCII)
        // Guard against a bogus "name" that is not printable ASCII.
        if (keyType.any { it.code < 0x21 || it.code > 0x7E }) return null
        return Info(
            keyType = keyType,
            base64 = encoder.encodeToString(blob),
            fingerprint = fingerprint(blob)
        )
    }

    /** `SHA256:<base64>` of SHA-256 over [blob], with base64 padding stripped (OpenSSH style). */
    fun fingerprint(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val encoded = encoder.encodeToString(digest).trimEnd('=')
        return "SHA256:$encoded"
    }

    private val encoder: Base64.Encoder = Base64.getEncoder()
}
