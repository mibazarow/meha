package com.andharness.ru.domain.ssh

/** How a registered SSH server authenticates (update.md §5.2). */
enum class SshAuthType {
    PASSWORD,
    KEY;

    companion object {
        /**
         * Parses `PASSWORD` / `KEY` case-insensitively, or `null` when the raw value is neither —
         * callers report the unknown value instead of guessing a default.
         */
        fun from(raw: String?): SshAuthType? {
            val trimmed = raw?.trim() ?: return null
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        }

        /** The stored name of this type (what goes into the `authType` column). */
        fun nameOf(type: SshAuthType): String = type.name
    }
}
