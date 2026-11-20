package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One alternative name of a [PersonEntity] (update.md §4.3, table `person_aliases`), e.g. "Серёга",
 * "Serega", "Сергей Петрович". Aliases feed the fuzzy step of person resolution (update.md §4.5)
 * and are stored trimmed but not case-folded; matching is case-insensitive.
 *
 * The unique index on (`personId`, `alias`) keeps one person from accumulating the same alias twice,
 * while the index on `alias` serves the reverse lookup "which people are called X".
 */
@Entity(
    tableName = "person_aliases",
    indices = [
        Index(value = ["personId", "alias"], unique = true),
        Index("alias")
    ]
)
data class PersonAliasEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Owning person ([PersonEntity.id]). */
    val personId: Long,
    /** The alias itself (trimmed). */
    val alias: String
)
