package com.andharness.ru.domain.communications

/**
 * How a recording in the `recordings` table came to exist.
 *
 * Stored in the database as the enum [name] (see
 * [com.andharness.ru.data.db.entity.RecordingEntity.sourceType]); never as an ordinal, so
 * reordering the constants is safe.
 */
enum class RecordingSourceType {
    /** Captured from the device's built-in dictaphone/app directory during import. */
    DICTAPHONE,

    /** Started explicitly by the user or the agent. */
    MANUAL,

    /** A voice message pulled out of a messenger (WhatsApp/Telegram/...). */
    MESSENGER_VOICE,

    /** Imported from an external file the user shared with the app. */
    IMPORTED;

    companion object {
        /** Parses the stored name, or `null` when it is not a known source type. */
        fun from(raw: String?): RecordingSourceType? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}
