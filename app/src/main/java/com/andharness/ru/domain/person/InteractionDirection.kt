package com.andharness.ru.domain.person


/** The direction of an [Interaction] (update.md §4.3). */
enum class InteractionDirection {
    INCOMING,
    OUTGOING,
    MISSED,
    UNKNOWN;

    companion object {
        /** Parses the stored name, falling back to [UNKNOWN] for anything unknown. */
        fun from(raw: String?): InteractionDirection =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
}
