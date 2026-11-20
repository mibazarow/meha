package com.andharness.ru.domain.communications

/**
 * One audio recording tracked by the "Communications" feature, as the domain layer sees it.
 *
 * Only metadata about the audio file is carried here; the audio bytes live on the file system
 * ([filePath] is either an absolute path or a SAF URI) and are never copied into the database.
 * `sourceType`/`transcriptStatus` are the enum companions of the persisted names, so the database
 * schema stays stable across enum reordering.
 *
 * Android-free on purpose (like [com.andharness.ru.domain.person.Person]) so it is unit-testable on
 * the JVM; the entity mapping lives in the data layer.
 *
 * @param id row id; `0` for a recording not stored yet.
 * @param personId soft link to a person once the recording is attributed to one.
 * @param errorText failure reason when [transcriptStatus] is [TranscriptStatus.FAILED].
 */
data class Recording(
    val id: Long,
    val sourceType: RecordingSourceType,
    val filePath: String,
    val displayName: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val sourcePackage: String?,
    val transcriptStatus: TranscriptStatus,
    val keepFlag: Boolean,
    val personId: Long?,
    val errorText: String?,
    val createdAt: Long,
    val updatedAt: Long
)
