package com.andharness.ru.data.db

/**
 * Raw SQL of the "Communications" tables added in the Room migration 35 -> 36: `recordings`,
 * `transcript_segments`, `transcript_vectors`, `speakers` and `speaker_voice_embeddings` with their
 * indices. The FTS4 search index over transcript segments lives in [TranscriptFtsSchemaSql].
 *
 * The statements live here as named constants (like [PersonSchemaSql] / [MemoryVectorSchemaSql]) so
 * a pure JVM unit test can cross-check them without a device. Room re-validates every migrated
 * database at runtime
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions); this keeps a
 * drift visible earlier.
 *
 * Every statement is non-destructive: it only creates new tables/indices. Index names follow Room's
 * convention `index_<table>_<col>[_<col>...]`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 */
object CommunicationsSchemaSql {

    // ---- recordings ----

    const val CREATE_RECORDINGS_TABLE =
        "CREATE TABLE IF NOT EXISTS `recordings` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sourceType` TEXT NOT NULL, " +
            "`filePath` TEXT NOT NULL, " +
            "`displayName` TEXT, " +
            "`durationMs` INTEGER NOT NULL, " +
            "`sizeBytes` INTEGER NOT NULL, " +
            "`sourcePackage` TEXT, " +
            "`transcriptStatus` TEXT NOT NULL, " +
            "`keepFlag` INTEGER NOT NULL, " +
            "`personId` INTEGER, " +
            "`errorText` TEXT, " +
            "`createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL)"

    const val CREATE_RECORDINGS_TRANSCRIPT_STATUS_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_recordings_transcriptStatus` ON `recordings` (`transcriptStatus`)"

    const val CREATE_RECORDINGS_CREATED_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_recordings_createdAt` ON `recordings` (`createdAt`)"

    const val CREATE_RECORDINGS_PERSON_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_recordings_personId` ON `recordings` (`personId`)"

    // ---- transcript_segments ----

    const val CREATE_TRANSCRIPT_SEGMENTS_TABLE =
        "CREATE TABLE IF NOT EXISTS `transcript_segments` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`recordingId` INTEGER NOT NULL, " +
            "`startMs` INTEGER NOT NULL, " +
            "`endMs` INTEGER NOT NULL, " +
            "`text` TEXT NOT NULL, " +
            "`speakerId` INTEGER, " +
            "`confidence` REAL, " +
            "`createdAt` INTEGER NOT NULL)"

    const val CREATE_TRANSCRIPT_SEGMENTS_RECORDING_START_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_transcript_segments_recordingId_startMs` " +
            "ON `transcript_segments` (`recordingId`, `startMs`)"

    const val CREATE_TRANSCRIPT_SEGMENTS_SPEAKER_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_transcript_segments_speakerId` ON `transcript_segments` (`speakerId`)"

    // ---- transcript_vectors ----

    const val CREATE_TRANSCRIPT_VECTORS_TABLE =
        "CREATE TABLE IF NOT EXISTS `transcript_vectors` (" +
            "`segmentId` INTEGER NOT NULL, " +
            "`dim` INTEGER NOT NULL, " +
            "`vector` BLOB NOT NULL, " +
            "`model` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`segmentId`))"

    const val CREATE_TRANSCRIPT_VECTORS_MODEL_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_transcript_vectors_model` ON `transcript_vectors` (`model`)"

    // ---- speakers ----

    const val CREATE_SPEAKERS_TABLE =
        "CREATE TABLE IF NOT EXISTS `speakers` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT, " +
            "`isSelf` INTEGER NOT NULL, " +
            "`personId` INTEGER, " +
            "`voiceModel` TEXT, " +
            "`voiceDim` INTEGER, " +
            "`centroid` BLOB, " +
            "`sampleCount` INTEGER NOT NULL, " +
            "`confidence` REAL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL)"

    const val CREATE_SPEAKERS_PERSON_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_speakers_personId` ON `speakers` (`personId`)"

    const val CREATE_SPEAKERS_IS_SELF_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_speakers_isSelf` ON `speakers` (`isSelf`)"

    // ---- speaker_voice_embeddings ----

    const val CREATE_SPEAKER_VOICE_EMBEDDINGS_TABLE =
        "CREATE TABLE IF NOT EXISTS `speaker_voice_embeddings` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`speakerId` INTEGER NOT NULL, " +
            "`recordingId` INTEGER, " +
            "`dim` INTEGER NOT NULL, " +
            "`vector` BLOB NOT NULL, " +
            "`model` TEXT NOT NULL, " +
            "`quality` REAL, " +
            "`createdAt` INTEGER NOT NULL)"

    const val CREATE_SPEAKER_VOICE_EMBEDDINGS_SPEAKER_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_speaker_voice_embeddings_speakerId` " +
            "ON `speaker_voice_embeddings` (`speakerId`)"

    const val CREATE_SPEAKER_VOICE_EMBEDDINGS_MODEL_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_speaker_voice_embeddings_model` " +
            "ON `speaker_voice_embeddings` (`model`)"

    /** Every statement of the migration's table part, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_RECORDINGS_TABLE,
        CREATE_RECORDINGS_TRANSCRIPT_STATUS_INDEX,
        CREATE_RECORDINGS_CREATED_AT_INDEX,
        CREATE_RECORDINGS_PERSON_INDEX,
        CREATE_TRANSCRIPT_SEGMENTS_TABLE,
        CREATE_TRANSCRIPT_SEGMENTS_RECORDING_START_INDEX,
        CREATE_TRANSCRIPT_SEGMENTS_SPEAKER_INDEX,
        CREATE_TRANSCRIPT_VECTORS_TABLE,
        CREATE_TRANSCRIPT_VECTORS_MODEL_INDEX,
        CREATE_SPEAKERS_TABLE,
        CREATE_SPEAKERS_PERSON_INDEX,
        CREATE_SPEAKERS_IS_SELF_INDEX,
        CREATE_SPEAKER_VOICE_EMBEDDINGS_TABLE,
        CREATE_SPEAKER_VOICE_EMBEDDINGS_SPEAKER_INDEX,
        CREATE_SPEAKER_VOICE_EMBEDDINGS_MODEL_INDEX,
    )
}
