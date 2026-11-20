package com.andharness.ru.domain.person


/**
 * One identity (handle) of a [Person].
 *
 * @param value the **normalized** handle (see [IdentityNormalizer]).
 * @param appPackage the app the handle belongs to; empty for phone/email/other, and what keeps the
 *   same messenger handle in two apps distinct.
 * @param label optional display hint, e.g. "рабочий".
 */
data class PersonIdentity(
    val id: Long,
    val personId: Long,
    val type: IdentityType,
    val value: String,
    val appPackage: String,
    val label: String?
)
