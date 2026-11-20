package com.andharness.ru.domain.security

import java.io.File

/**
 * Pure, Android-free decision logic for encrypting the existing personal database (update.md
 * idea #7). It answers one question: given whether the database file exists and what its first
 * bytes are, should Room create a fresh encrypted DB, migrate a legacy plaintext one, or just open
 * the already-encrypted file?
 *
 * A plaintext SQLite database always starts with the 16-byte magic string `SQLite format 3\0`.
 * An SQLCipher database deliberately starts with random salt bytes, so the magic string is absent —
 * and, crucially, that test needs no key, which is what makes the migration decision safe to take
 * before the database is opened.
 *
 * Official references:
 * - SQLite database file format — https://www.sqlite.org/fileformat.html
 */
object DatabaseEncryptionPlanner {

    /** The 16 magic bytes at the start of every unencrypted SQLite database file. */
    val PLAINTEXT_SQLITE_HEADER: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** Number of leading bytes to read when sniffing the database file. */
    const val HEADER_BYTES: Int = 16

    /** True when [header] starts with the plaintext SQLite magic string. */
    fun isPlaintextSqliteHeader(header: ByteArray?): Boolean {
        if (header == null || header.size < PLAINTEXT_SQLITE_HEADER.size) return false
        return PLAINTEXT_SQLITE_HEADER.indices.all { header[it] == PLAINTEXT_SQLITE_HEADER[it] }
    }

    /**
     * Reads up to [count] leading bytes of [file], or `null` when the file is absent, unreadable or
     * shorter than one byte. Never throws.
     */
    fun readHeader(file: File, count: Int = HEADER_BYTES): ByteArray? {
        if (!file.isFile) return null
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(count)
                var read = 0
                while (read < count) {
                    val n = input.read(buffer, read, count - read)
                    if (n <= 0) break
                    read += n
                }
                if (read <= 0) null else buffer.copyOf(read)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** The plan for a database file that may or may not exist and may or may not be encrypted. */
    fun plan(databaseExists: Boolean, header: ByteArray?): DatabaseEncryptionPlan = when {
        !databaseExists -> DatabaseEncryptionPlan.FRESH_ENCRYPTED
        isPlaintextSqliteHeader(header) -> DatabaseEncryptionPlan.MIGRATE_PLAINTEXT
        else -> DatabaseEncryptionPlan.OPEN_ENCRYPTED
    }
}
