package com.andharness.ru.domain.message


/** What the caller asked for: a specific channel or [AUTO] (let the resolver decide). */
enum class MessageChannelRequest {
    AUTO,
    SMS,
    MESSENGER;

    companion object {
        /** Parses the tool argument, defaulting to `null` for an unknown/blank value. */
        fun from(raw: String?): MessageChannelRequest? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return AUTO
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        }
    }
}
