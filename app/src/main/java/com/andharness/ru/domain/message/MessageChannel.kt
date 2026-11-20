package com.andharness.ru.domain.message


/** Delivery channel of a composed message (update.md §4.7). */
enum class MessageChannel {
    SMS,
    MESSENGER;

    companion object {
        /** Parses a stored/requested channel name, or `null` when it is not a known channel. */
        fun from(raw: String?): MessageChannel? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}
