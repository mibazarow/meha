package com.andharness.ru.domain.person


/**
 * A person of the user's "unified personal base" (update.md §4.3): a container that ties together
 * identities, aliases and interactions so the agent does not have to re-scan the phone on every
 * request and can tell "which Серёга" is meant.
 *
 * @param displayName human-readable name; not unique.
 * @param notes free-form notes, empty when none.
 * @param createdAt epoch millis when the person was first created.
 * @param updatedAt epoch millis when the person was last changed.
 */
data class Person(
    val id: Long,
    val displayName: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long
)
