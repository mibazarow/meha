package com.andharness.ru.domain.security


/** What must happen to the personal database before Room opens it. */
enum class DatabaseEncryptionPlan {

    /** No database file yet: Room may create it directly with SQLCipher (empty encrypted DB). */
    FRESH_ENCRYPTED,

    /** A legacy plaintext SQLite file exists and must be copied into a new encrypted file first. */
    MIGRATE_PLAINTEXT,

    /** The file is already encrypted (its header is not the SQLite magic string): open with the key. */
    OPEN_ENCRYPTED
}
