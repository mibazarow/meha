package com.andharness.ru.domain.messages

import com.andharness.ru.domain.common.Hashing

/**
 * Builders of the stable [StoredMessage.externalRef] dedup keys, split out of the Android ingest
 * paths so they are pure and unit-testable on the JVM.
 *
 * Every key embeds a short SHA-256 hash of the payload that identifies the message, so re-ingesting
 * the same source event produces the exact same key and is a no-op (the `externalRef` column is
 * UNIQUE). Only a **short** digest is kept — enough to avoid collisions, without stuffing a full
 * message body into the index.
 *
 * Note the hash is one-way and never reversible, so a body cannot leak back out of the key.
 */
object MessageExternalRefs {

    /** Number of SHA-256 bytes kept (24 hex chars): collision-safe, still short. */
    private const val HASH_BYTES = 12

    /**
     * Key of a **live** SMS broadcast. A broadcast carries no row id, so the sender, the timestamp and
     * the full body are hashed together: the same broadcast delivered twice maps to the same key.
     */
    fun smsLive(sender: String, at: Long, body: String): String =
        "sms:live:" + shortHash("$sender|$at|$body")

    /**
     * Key of one message captured from a notification: `notif:<package>:<key>:<index>:<hash(text|at)>`.
     *
     * [key] is the `StatusBarNotification` key (stable per conversation) and [index] the position in
     * the extracted message list; the text/time hash distinguishes the individual messages.
     */
    fun notification(packageName: String, key: String, index: Int, text: String, at: Long): String =
        "notif:$packageName:$key:$index:" + shortHash("$text|$at")

    /** Lowercase hex of the first [HASH_BYTES] of the SHA-256 of [input] (UTF-8). */
    fun shortHash(input: String): String = Hashing.sha256Hex(input).take(HASH_BYTES * 2)
}
