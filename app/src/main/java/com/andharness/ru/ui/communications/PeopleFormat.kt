package com.andharness.ru.ui.communications

import com.andharness.ru.domain.person.PersonIdentity

/**
 * Presentation-only helpers for the «Люди» list. Kept free of Compose/Android types so they are
 * unit-testable on the JVM (see `PeopleFormatTest`).
 */

/**
 * Short one-line identity hint for a person row: the first up-to-two normalized handles
 * (phone/email/messenger) joined by «·», or `null` when the person carries no usable identity.
 *
 * Blank values are skipped so the row never shows an empty separator; the list is already bounded
 * by the caller (the ViewModel reads one person's identities at a time).
 */
internal fun identityHint(identities: List<PersonIdentity>): String? = identities
    .asSequence()
    .map { it.value.trim() }
    .filter { it.isNotEmpty() }
    .take(2)
    .joinToString(" · ")
    .ifEmpty { null }
