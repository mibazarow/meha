package com.andharness.ru.domain.person


/** The channel an [Interaction] came through (update.md §4.3). */
enum class InteractionChannel {
    SMS,
    CALL,
    MESSENGER,
    EMAIL,
    CHAT;

    companion object {
        /** Parses the stored name, or `null` when it is not a known channel. */
        fun from(raw: String?): InteractionChannel? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}
