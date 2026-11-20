package com.andharness.ru.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import com.andharness.ru.data.db.AppDatabase
import com.andharness.ru.data.db.encryption.AndroidKeystoreByteSealer
import com.andharness.ru.data.db.encryption.SharedPrefsPassphraseStore
import com.andharness.ru.data.db.encryption.SqlCipherDatabaseEncrypter
import com.andharness.ru.domain.security.DatabasePassphrase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Owns the personal [AppDatabase] instance and its SQLCipher passphrase. The ordered migration list
 * lives in [DbMigrations]; the per-domain DAO providers live in the `<Domain>DbModule` files.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** Name of the personal database file inside the app's `databases/` directory. */
    const val DATABASE_NAME = "andharness.db"

    /**
     * Supplies the SQLCipher passphrase (update.md idea #7). The raw passphrase is random and never
     * written to disk: only its Android-Keystore-sealed form is persisted.
     */
    @Provides
    @Singleton
    fun provideDatabasePassphrase(
        @ApplicationContext context: Context
    ): DatabasePassphrase = DatabasePassphrase(
        store = SharedPrefsPassphraseStore(
            context.getSharedPreferences(
                SharedPrefsPassphraseStore.PREFS_FILE,
                Context.MODE_PRIVATE
            )
        ),
        sealer = AndroidKeystoreByteSealer()
    )

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        passphrase: DatabasePassphrase
    ): AppDatabase {
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        val encrypter = SqlCipherDatabaseEncrypter()
        // Encryption is best-effort by design: if SQLCipher cannot be loaded, or the legacy
        // plaintext database cannot be migrated and verified, the app keeps using the plaintext
        // database instead of refusing to start. Nothing is deleted on the failure path.
        val setup = runCatching { encrypter.prepare(databaseFile, passphrase.get()) }
            .getOrElse { error ->
                Log.e(
                    "ANDHARNESS_DB",
                    "SQLCipher: подготовка не удалась (${error::class.java.name}: ${error.message}) — " +
                        "база остаётся незашифрованной.",
                    error
                )
                // Last resort: if the passphrase is gone but the database is encrypted, put the
                // kept plaintext backup back so the app still has its data (rollback path).
                encrypter.restorePlaintextBackupIfEncrypted(databaseFile)
                null
            }
        return roomBuilder(context)
            .apply { setup?.factory?.let { openHelperFactory(it) } }
            .build()
    }

    private fun roomBuilder(context: Context): RoomDatabase.Builder<AppDatabase> =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DATABASE_NAME
        ).addMigrations(
            *DbMigrations.ALL
        )
}
