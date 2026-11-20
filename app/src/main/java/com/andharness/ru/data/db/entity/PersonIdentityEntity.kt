package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One identity (handle) of a [PersonEntity] (update.md §4.3, table `person_identities`).
 *
 * [value] is stored in its **normalized** form (see
 * [com.andharness.ru.domain.person.IdentityNormalizer]); [type] holds the
 * [com.andharness.ru.domain.person.IdentityType] name and [appPackage] scopes a messenger handle to
 * its application (`""` for phone/email/other). The unique index on
 * (`type`, `value`, `appPackage`) enforces that the same handle is not indexed twice; the same
 * handle in two different apps stays two distinct rows.
 *
 * [appPackage] is non-null on purpose: SQLite treats `NULL`s as distinct in a unique index, which
 * would silently allow duplicates. [label] stays nullable — it is only a display hint
 * (e.g. "рабочий").
 */
@Entity(
    tableName = "person_identities",
    indices = [
        Index(value = ["type", "value", "appPackage"], unique = true),
        Index("personId")
    ]
)
data class PersonIdentityEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Owning person ([PersonEntity.id]). */
    val personId: Long,
    /** [com.andharness.ru.domain.person.IdentityType] name: `PHONE` | `EMAIL` | `MESSENGER` | `OTHER`. */
    val type: String,
    /** Normalized handle value (E.164-like phone, lower-case email, trimmed messenger id, ...). */
    val value: String,
    /** Package the handle belongs to; `""` for channels that have no app (phone/email/other). */
    val appPackage: String,
    /** Optional display hint, e.g. "рабочий". */
    val label: String?
)
