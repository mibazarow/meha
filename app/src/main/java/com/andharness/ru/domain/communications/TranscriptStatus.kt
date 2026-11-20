package com.andharness.ru.domain.communications

/**
 * Lifecycle of the speech-to-text pass over one recording.
 *
 * Stored in the database as the enum [name] (see
 * [com.andharness.ru.data.db.entity.RecordingEntity.transcriptStatus]); the name makes the state
 * stable across app versions even if new states are inserted in the middle.
 */
enum class TranscriptStatus {
    /** Discovered, not queued yet. */
    PENDING,

    /** A transcription run currently owns this recording. */
    RUNNING,

    /** Finished successfully; segments are available. */
    DONE,

    /** The run failed; [com.andharness.ru.data.db.entity.RecordingEntity.errorText] holds why. */
    FAILED,

    /** Deliberately not transcribed (too short, unsupported, user opt-out). */
    SKIPPED;

    companion object {
        /** Parses the stored name, or `null` when it is not a known status. */
        fun from(raw: String?): TranscriptStatus? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}
