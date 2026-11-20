package com.andharness.ru.data.db.encryption

import android.util.Log
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.andharness.ru.domain.security.DatabaseEncryptionPlan
import com.andharness.ru.domain.security.DatabaseEncryptionPlanner
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Turns the app's personal Room database into an SQLCipher-encrypted one, safely migrating the
 * existing plaintext file (update.md idea #7).
 *
 * Responsibilities, in order:
 * 1. **Recover** from an interrupted earlier migration (a `.plain.bak` without a main file, or a
 *    leftover `.migrating` temp file).
 * 2. **Decide** what to do from the file header alone (see [DatabaseEncryptionPlanner]).
 * 3. **Migrate** a legacy plaintext database by `ATTACH`ing it to a brand-new encrypted database
 *    and running `sqlcipher_export('main', 'plaintext')`, then verifying the result table by table
 *    before swapping files. The plaintext file is kept as `<name>.plain.bak` — the rollback path.
 * 4. Hand Room a [SupportOpenHelperFactory] bound to the passphrase.
 *
 * Every failure is converted into a [SqlCipherSetup] with `factory == null`, so the worst case is
 * "the database stays plaintext", never "the database is corrupt" or "the app will not start".
 *
 * Official references:
 * - SQLCipher `sqlcipher_export()` — https://www.zetetic.net/sqlcipher/sqlcipher-api/#sqlcipher_export
 * - SQLCipher `ATTACH ... KEY ''` (plaintext source) — https://www.zetetic.net/sqlcipher/sqlcipher-api/#attach
 * - SQLCipher for Android / Room — https://github.com/sqlcipher/sqlcipher-android
 */
class SqlCipherDatabaseEncrypter(
    private val log: (String) -> Unit = { Log.i(TAG, it) }
) {

    /**
     * Prepares [databaseFile] for encrypted access with [passphrase] and returns the Room helper
     * factory. Never throws: on any failure the returned setup carries `factory == null`.
     */
    fun prepare(databaseFile: File, passphrase: ByteArray): SqlCipherSetup {
        recoverInterruptedMigration(databaseFile)

        val header = DatabaseEncryptionPlanner.readHeader(databaseFile)
        val plan = DatabaseEncryptionPlanner.plan(databaseFile.isFile, header)
        return try {
            System.loadLibrary(NATIVE_LIBRARY)
            when (plan) {
                DatabaseEncryptionPlan.FRESH_ENCRYPTED -> {
                    log("SQLCipher: базы ещё нет — создаю новую зашифрованную (${databaseFile.name}).")
                    SqlCipherSetup(plan, factory(passphrase), "создана новая зашифрованная база")
                }

                DatabaseEncryptionPlan.OPEN_ENCRYPTED -> {
                    log("SQLCipher: база уже зашифрована — открываю с ключом (${databaseFile.name}).")
                    SqlCipherSetup(plan, factory(passphrase), "база уже была зашифрована")
                }

                DatabaseEncryptionPlan.MIGRATE_PLAINTEXT -> {
                    val note = migrate(databaseFile, passphrase)
                    log("SQLCipher: миграция завершена — $note")
                    SqlCipherSetup(plan, factory(passphrase), note)
                }
            }
        } catch (t: Throwable) {
            val reason = "${t::class.java.name}: ${t.message}"
            log("SQLCipher недоступен/миграция не удалась ($reason) — база остаётся незашифрованной.")
            SqlCipherSetup(plan, null, "шифрование не включено: $reason")
        }
    }

    /**
     * Rollback of last resort used when the passphrase can no longer be unsealed (Keystore reset).
     * If the database is encrypted but its plaintext backup is present, the backup is put back so
     * the app keeps working with its data. Returns `true` when a restore happened.
     */
    fun restorePlaintextBackupIfEncrypted(databaseFile: File): Boolean {
        return try {
            if (!databaseFile.isFile) return false
            val header = DatabaseEncryptionPlanner.readHeader(databaseFile)
            if (DatabaseEncryptionPlanner.isPlaintextSqliteHeader(header)) return false
            val backup = plaintextBackup(databaseFile)
            if (!backup.isFile) return false
            if (!databaseFile.delete()) return false
            deleteSidecars(databaseFile)
            if (!backup.renameTo(databaseFile)) return false
            log("SQLCipher: ключ недоступен — восстановил незашифрованную базу из ${backup.name}.")
            true
        } catch (t: Throwable) {
            log("SQLCipher: не удалось восстановить незашифрованную базу: ${t.message}")
            false
        }
    }

    // ---- migration ---------------------------------------------------------------------------

    private fun migrate(databaseFile: File, passphrase: ByteArray): String {
        requireNotNull(databaseFile.parentFile) { "У базы нет каталога: $databaseFile" }
        val migrating = migratingFile(databaseFile)
        val backup = plaintextBackup(databaseFile)

        // 1. Flush the plaintext WAL so the source file is self-contained, and count its rows.
        val sourceCounts = checkpointAndCountPlaintext(databaseFile)
        val sourceVersion = readPlaintextUserVersion(databaseFile)
        log("SQLCipher: исходная база v$sourceVersion, таблиц ${sourceCounts.size}, строк ${sourceCounts.values.sum()}.")

        // 2. Copy the whole plaintext database into a fresh encrypted file.
        migrating.delete()
        deleteSidecars(migrating)
        val encrypted = SQLiteDatabase.openOrCreateDatabase(migrating, passphrase, null, null)
        try {
            val source = sqlLiteral(databaseFile.absolutePath)
            encrypted.rawExecSQL("ATTACH DATABASE $source AS plaintext KEY ''")
            encrypted.rawExecSQL("SELECT sqlcipher_export('main', 'plaintext')")
            // sqlcipher_export deliberately does not copy user_version; Room reads it to decide
            // which migrations to run, so it must be carried over explicitly.
            encrypted.rawExecSQL("PRAGMA main.user_version = $sourceVersion")
            encrypted.rawExecSQL("DETACH DATABASE plaintext")

            val integrity = readSingleString(encrypted, "PRAGMA cipher_integrity_check")
            require(integrity == null || integrity == "ok") {
                "Проверка целостности зашифрованной базы не прошла: $integrity"
            }
            val targetCounts = countTables(encrypted)
            require(targetCounts == sourceCounts) {
                "Число строк после копирования не совпало: было $sourceCounts, стало $targetCounts"
            }
            val targetVersion = readSingleLong(encrypted, "PRAGMA main.user_version")
            require(targetVersion == sourceVersion) {
                "Версия схемы после копирования не совпала: было $sourceVersion, стало $targetVersion"
            }
        } finally {
            encrypted.close()
        }

        // 3. Swap the files, keeping the plaintext database as the rollback point.
        backup.delete()
        deleteSidecars(backup)
        require(databaseFile.renameTo(backup)) {
            "Не удалось переименовать ${databaseFile.name} в ${backup.name}"
        }
        deleteSidecars(databaseFile)
        if (!migrating.renameTo(databaseFile)) {
            backup.renameTo(databaseFile)
            throw IllegalStateException("Не удалось поставить зашифрованную базу на место — вернул исходную.")
        }
        deleteSidecars(migrating)
        return "таблиц ${sourceCounts.size}, строк ${sourceCounts.values.sum()}, " +
            "резервная незашифрованная копия: ${backup.name}"
    }

    /** Recovers from a crash between the two renames of the swap, and drops stale temp files. */
    private fun recoverInterruptedMigration(databaseFile: File) {
        try {
            val migrating = migratingFile(databaseFile)
            val backup = plaintextBackup(databaseFile)
            if (!databaseFile.isFile && backup.isFile) {
                if (backup.renameTo(databaseFile)) {
                    log("SQLCipher: прерванная миграция — вернул ${backup.name} на место.")
                }
            }
            if (migrating.isFile) migrating.delete()
            listOf(migrating, backup).forEach(::deleteSidecars)
        } catch (t: Throwable) {
            log("SQLCipher: не удалось проверить прерванную миграцию: ${t.message}")
        }
    }

    private fun factory(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory =
        SupportOpenHelperFactory(passphrase.copyOf())

    // ---- plaintext source --------------------------------------------------------------------

    /**
     * Opens the plaintext database with the framework SQLite, checkpoints its WAL into the main file
     * (so a later rename cannot orphan committed rows) and returns exact per-table row counts.
     */
    private fun checkpointAndCountPlaintext(file: File): Map<String, Long> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )
        try {
            // PRAGMA returns a row, so rawQuery (not execSQL) is required.
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val tables = mutableListOf<String>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            return tables.associateWith { table ->
                db.rawQuery("SELECT COUNT(*) FROM ${quote(table)}", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                }
            }
        } finally {
            db.close()
        }
    }

    private fun readPlaintextUserVersion(file: File): Long {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            return db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        } finally {
            db.close()
        }
    }

    // ---- encrypted target --------------------------------------------------------------------

    private fun countTables(db: SQLiteDatabase): Map<String, Long> {
        val tables = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        return tables.associateWith { table ->
            db.query("SELECT COUNT(*) FROM ${quote(table)}").use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }
    }

    private fun readSingleLong(db: SQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun readSingleString(db: SQLiteDatabase, sql: String): String? =
        db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    // ---- small file helpers ------------------------------------------------------------------

    private fun migratingFile(databaseFile: File): File =
        File(databaseFile.parentFile, "${databaseFile.name}.migrating")

    private fun plaintextBackup(databaseFile: File): File =
        File(databaseFile.parentFile, "${databaseFile.name}.plain.bak")

    /** Removes `-wal`/`-shm`/`-journal` siblings of [file]. */
    private fun deleteSidecars(file: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(file.parentFile, file.name + suffix).takeIf { it.isFile }?.delete()
        }
    }

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    private fun sqlLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    companion object {
        private const val TAG = "ANDHARNESS_DB"

        /** Native library bundled by `net.zetetic:sqlcipher-android` (must be loaded explicitly). */
        const val NATIVE_LIBRARY = "sqlcipher"
    }
}
