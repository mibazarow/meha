package com.andharness.ru.data.communications

import com.andharness.ru.data.db.entity.RecordingEntity
import com.andharness.ru.data.db.entity.SpeakerEntity
import com.andharness.ru.data.db.entity.SpeakerVoiceEmbeddingEntity
import com.andharness.ru.data.db.entity.TranscriptSegmentEntity
import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.RecordingSourceType
import com.andharness.ru.domain.communications.Speaker
import com.andharness.ru.domain.communications.SpeakerVoiceEmbedding
import com.andharness.ru.domain.communications.TranscriptSegment
import com.andharness.ru.domain.communications.TranscriptStatus
import com.andharness.ru.domain.retrieval.VectorCodec

/**
 * Entity ⇄ domain mapping for the "Communications" tables. Kept in the data layer (not in the
 * Android-free domain) so the domain models stay free of Room dependencies.
 *
 * Enum columns hold the enum [name][Enum.name]; an unknown stored name (e.g. a row written by a
 * newer version) falls back to a safe default instead of throwing
 * ([RecordingSourceType.IMPORTED] / [TranscriptStatus.PENDING]).
 *
 * Voice vectors are stored as little-endian float32 `BLOB`s via
 * [VectorCodec]; a corrupt/short blob decodes to an empty vector, never an exception.
 */
fun RecordingEntity.toDomain(): Recording = Recording(
    id = id,
    sourceType = RecordingSourceType.from(sourceType) ?: RecordingSourceType.IMPORTED,
    filePath = filePath,
    displayName = displayName,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    sourcePackage = sourcePackage,
    transcriptStatus = TranscriptStatus.from(transcriptStatus) ?: TranscriptStatus.PENDING,
    keepFlag = keepFlag,
    personId = personId,
    errorText = errorText,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Recording.toEntity(): RecordingEntity = RecordingEntity(
    id = id,
    sourceType = sourceType.name,
    filePath = filePath,
    displayName = displayName,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    sourcePackage = sourcePackage,
    transcriptStatus = transcriptStatus.name,
    keepFlag = keepFlag,
    personId = personId,
    errorText = errorText,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun TranscriptSegmentEntity.toDomain(): TranscriptSegment = TranscriptSegment(
    id = id,
    recordingId = recordingId,
    startMs = startMs,
    endMs = endMs,
    text = text,
    speakerId = speakerId,
    confidence = confidence,
    createdAt = createdAt
)

fun TranscriptSegment.toEntity(recordingId: Long): TranscriptSegmentEntity = TranscriptSegmentEntity(
    id = id,
    recordingId = recordingId,
    startMs = startMs,
    endMs = endMs,
    text = text,
    speakerId = speakerId,
    confidence = confidence,
    createdAt = createdAt
)

fun SpeakerEntity.toDomain(): Speaker = Speaker(
    id = id,
    name = name,
    isSelf = isSelf,
    personId = personId,
    voiceModel = voiceModel,
    voiceDim = voiceDim,
    centroid = centroid?.let { VectorCodec.decode(it) },
    sampleCount = sampleCount,
    confidence = confidence,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Speaker.toEntity(): SpeakerEntity = SpeakerEntity(
    id = id,
    name = name,
    isSelf = isSelf,
    personId = personId,
    voiceModel = voiceModel,
    voiceDim = voiceDim,
    centroid = centroid?.let { VectorCodec.encode(it) },
    sampleCount = sampleCount,
    confidence = confidence,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun SpeakerVoiceEmbeddingEntity.toDomain(): SpeakerVoiceEmbedding = SpeakerVoiceEmbedding(
    id = id,
    speakerId = speakerId,
    recordingId = recordingId,
    dim = dim,
    vector = VectorCodec.decode(vector),
    model = model,
    quality = quality,
    createdAt = createdAt
)

fun SpeakerVoiceEmbedding.toEntity(): SpeakerVoiceEmbeddingEntity = SpeakerVoiceEmbeddingEntity(
    id = id,
    speakerId = speakerId,
    recordingId = recordingId,
    dim = dim,
    vector = VectorCodec.encode(vector),
    model = model,
    quality = quality,
    createdAt = createdAt
)
