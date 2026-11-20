package com.andharness.ru.domain.common

import java.io.InputStream
import java.security.MessageDigest

/**
 * Pure, Android-free hashing helpers shared by the audit chain, the LLM catalog cache and the files
 * tooling. Every hex result is lowercase, two digits per byte, and locale-independent, so callers
 * that previously formatted their own digest keep byte-identical output.
 *
 * Official reference: `java.security.MessageDigest` —
 * https://developer.android.com/reference/java/security/MessageDigest
 */
object Hashing {

    /** Identity for a blank/absent API key (keyless local servers compare equal). */
    const val NO_KEY_FINGERPRINT = "none"

    /** 16 hex chars = first 64 bits of SHA-256; plenty for cache/request identity. */
    const val FINGERPRINT_HEX_LENGTH = 16

    /** Chunk size for the streaming [sha256Hex] overload. */
    private const val STREAM_BUFFER_SIZE = 8 * 1024

    private const val HEX = "0123456789abcdef"

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String =
        toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Lowercase hex SHA-256 of the UTF-8 bytes of [text]. */
    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * Streaming SHA-256 of [stream]: the digest is fed fixed-size chunks, so an arbitrarily large
     * source is never loaded into memory. The stream is NOT closed here — the caller owns it.
     */
    fun sha256Hex(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    /** Lowercase hex of [bytes], two digits per byte; locale-independent. */
    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            append(HEX[(byte.toInt() shr 4) and 0x0F])
            append(HEX[byte.toInt() and 0x0F])
        }
    }

    /**
     * Short non-reversible fingerprint of a credential: [NO_KEY_FINGERPRINT] for a blank/absent
     * value, otherwise the first [FINGERPRINT_HEX_LENGTH] lowercase hex chars of SHA-256 over the
     * RAW (untrimmed) UTF-8 value. Never store or log the raw credential.
     */
    fun keyFingerprint(value: String?): String {
        if (value.isNullOrBlank()) return NO_KEY_FINGERPRINT
        return sha256Hex(value).take(FINGERPRINT_HEX_LENGTH)
    }
}
