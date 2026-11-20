package com.andharness.ru.domain.security

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the encryption migration *decision* logic (update.md idea #7). The SQLCipher open
 * itself is verified on-device; what can be pinned down deterministically on the JVM is the branch
 * selection, which decides whether the app copies a legacy plaintext database or not.
 */
class DatabaseEncryptionPlannerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val plaintextHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    @Test
    fun `plaintext sqlite magic is recognised`() {
        assertTrue(DatabaseEncryptionPlanner.isPlaintextSqliteHeader(plaintextHeader))
    }

    @Test
    fun `random sqlcipher salt is not a plaintext header`() {
        val salt = ByteArray(16) { (it * 7 + 1).toByte() }
        assertFalse(DatabaseEncryptionPlanner.isPlaintextSqliteHeader(salt))
    }

    @Test
    fun `null and truncated headers are not plaintext`() {
        assertFalse(DatabaseEncryptionPlanner.isPlaintextSqliteHeader(null))
        assertFalse(DatabaseEncryptionPlanner.isPlaintextSqliteHeader(ByteArray(0)))
        assertFalse(DatabaseEncryptionPlanner.isPlaintextSqliteHeader(plaintextHeader.copyOf(15)))
    }

    @Test
    fun `header longer than the magic is still recognised`() {
        val longer = plaintextHeader + ByteArray(32) { 0x11 }
        assertTrue(DatabaseEncryptionPlanner.isPlaintextSqliteHeader(longer))
    }

    @Test
    fun `missing database means fresh encrypted`() {
        assertEquals(
            DatabaseEncryptionPlan.FRESH_ENCRYPTED,
            DatabaseEncryptionPlanner.plan(databaseExists = false, header = null)
        )
    }

    @Test
    fun `existing plaintext database must be migrated`() {
        assertEquals(
            DatabaseEncryptionPlan.MIGRATE_PLAINTEXT,
            DatabaseEncryptionPlanner.plan(databaseExists = true, header = plaintextHeader)
        )
    }

    @Test
    fun `existing non-plaintext database is already encrypted`() {
        assertEquals(
            DatabaseEncryptionPlan.OPEN_ENCRYPTED,
            DatabaseEncryptionPlanner.plan(
                databaseExists = true,
                header = ByteArray(16) { (it * 13 + 5).toByte() }
            )
        )
    }

    @Test
    fun `readHeader returns null for a missing file`() {
        assertNull(DatabaseEncryptionPlanner.readHeader(File(folder.root, "nope.db")))
    }

    @Test
    fun `readHeader returns the plaintext magic from a real file`() {
        val file = folder.newFile("plain.db")
        file.writeBytes(byteArrayOf(0, 1, 2, 3) + plaintextHeader)
        // The file does not start with the magic string, so it is not plaintext SQLite.
        val shifted = DatabaseEncryptionPlanner.readHeader(file)
        assertFalse(DatabaseEncryptionPlanner.isPlaintextSqliteHeader(shifted))

        val real = folder.newFile("real.db")
        real.writeBytes(plaintextHeader + ByteArray(500) { 0x22 })
        val header = DatabaseEncryptionPlanner.readHeader(real)
        assertEquals(DatabaseEncryptionPlanner.HEADER_BYTES, header?.size)
        assertTrue(DatabaseEncryptionPlanner.isPlaintextSqliteHeader(header))
    }

    @Test
    fun `readHeader handles a file shorter than the header size`() {
        val file = folder.newFile("short.db")
        file.writeBytes("SQLite fo".toByteArray(Charsets.US_ASCII))
        val header = DatabaseEncryptionPlanner.readHeader(file)
        assertEquals(9, header?.size)
        assertFalse(DatabaseEncryptionPlanner.isPlaintextSqliteHeader(header))
    }
}
