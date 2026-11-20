package com.andharness.ru.data.person

import com.andharness.ru.domain.person.IdentityNormalizer
import com.andharness.ru.domain.person.IdentityType

/**
 * Where an interaction should be filed (update.md §4.4).
 *
 * Either an already-resolved person ([Existing]) or a raw handle ([Identity]) that the indexer must
 * resolve/create first.
 */
sealed interface PersonRef {
    data class Existing(val personId: Long) : PersonRef

    data class Identity(
        val type: IdentityType,
        val value: String,
        val appPackage: String = IdentityNormalizer.NO_PACKAGE,
        val label: String? = null
    ) : PersonRef
}
