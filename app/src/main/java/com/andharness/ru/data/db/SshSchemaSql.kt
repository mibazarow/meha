package com.andharness.ru.data.db

/**
 * The raw SQL of the SSH-server-registry migration (update.md §5.2, §6 step 6): the `ssh_servers`
 * table plus its unique index on `name`.
 *
 * The statements live here as named constants instead of being inlined into the migration so that a
 * pure JVM unit test can cross-check them, byte for byte, against the schema Room expects in the
 * kapt-generated `AppDatabase_Impl` (`SshMigrationSqlTest`). Room itself re-validates every migrated
 * database at runtime
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions), so a drift here
 * would fail the migration anyway — the test merely catches it earlier and without a device.
 *
 * Non-destructive: it only creates a new table and its index, nothing existing is touched. No secret
 * is stored in this table (update.md §5.4): passwords/passphrases live in EncryptedSharedPreferences
 * and private keys are referenced by a SAF URI only.
 *
 * Index names follow Room's naming convention `index_<table>_<col>`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 */
object SshSchemaSql {

    const val CREATE_SSH_SERVERS_TABLE =
        "CREATE TABLE IF NOT EXISTS `ssh_servers` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`host` TEXT NOT NULL, " +
            "`port` INTEGER NOT NULL, " +
            "`user` TEXT NOT NULL, " +
            "`authType` TEXT NOT NULL, " +
            "`keyRef` TEXT, " +
            "`createdAt` INTEGER NOT NULL)"

    /** Unique: a server name addresses exactly one server (update.md §5.4, disambiguation). */
    const val CREATE_SSH_SERVERS_NAME_UNIQUE_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_ssh_servers_name` ON `ssh_servers` (`name`)"

    /** Every statement of the v18 -> v19 migration, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_SSH_SERVERS_TABLE,
        CREATE_SSH_SERVERS_NAME_UNIQUE_INDEX,
    )
}
