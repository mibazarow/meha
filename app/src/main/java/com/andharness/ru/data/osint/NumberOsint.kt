package com.andharness.ru.data.osint

import kotlinx.serialization.Serializable

/**
 * Cached OSINT result for one normalized number (update.md §2.5/§2.6). `summary` is always
 * human-readable and **never fabricated**: when nothing was found it carries an explicit
 * "not found" text.
 */

@Serializable
data class NumberOsint(
    val number: String,
    val summary: String,
    val sources: List<OsintSource> = emptyList(),
    val updatedAt: Long = 0L,
    /** True when this value came from the persisted cache instead of a fresh web search. */
    val fromCache: Boolean = false,
)
