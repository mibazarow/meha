package com.andharness.ru.domain.person


/** The kind of handle a [PersonIdentity] carries (update.md §4.3). */
enum class IdentityType {
    PHONE,
    EMAIL,
    MESSENGER,
    OTHER;

    companion object {
        /** Parses the stored name, falling back to [OTHER] for anything unknown. */
        fun from(raw: String?): IdentityType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OTHER
    }
}
