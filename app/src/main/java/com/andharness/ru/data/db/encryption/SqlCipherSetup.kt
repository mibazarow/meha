package com.andharness.ru.data.db.encryption

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.andharness.ru.domain.security.DatabaseEncryptionPlan

/**
 * Outcome of preparing the personal database for SQLCipher (update.md idea #7).
 *
 * [factory] is `null` when encryption could not be set up: the caller must then open the database
 * the old plaintext way rather than refuse to start. [note] is a short honest explanation (usually
 * Russian) that is logged and can be surfaced to the user.
 */
data class SqlCipherSetup(
    val plan: DatabaseEncryptionPlan,
    val factory: SupportSQLiteOpenHelper.Factory?,
    val note: String
) {
    val encrypted: Boolean get() = factory != null
}
