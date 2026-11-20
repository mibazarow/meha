package com.andharness.ru.domain.communications

import com.andharness.ru.data.communications.toDomain
import com.andharness.ru.data.communications.toEntity
import com.andharness.ru.data.db.entity.RecordingEntity
import com.andharness.ru.data.db.entity.SpeakerEntity
import com.andharness.ru.data.db.entity.SpeakerVoiceEmbeddingEntity
import com.andharness.ru.data.db.entity.TranscriptSegmentEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the entity ⇄ domain mappings for the "Communications" tables: full round
 * trips, null-safe optional fields, the unknown-enum-name fallback, and the [FloatArray] voice
 * vectors that go through the little-endian codec.
 */
class CommunicationsMappingsTest {

    @Test
    fun recordingRoundTripsAllFields() {
        val entity = RecordingEntity(
            id = 7,
            sourceType = RecordingSourceType.MESSENGER_VOICE.name,
            filePath = "content://voice/42",
            displayName = "Voice from Ana",
            durationMs = 12_500,
            sizeBytes = 98_765,
            sourcePackage = "org.telegram.messenger",
            transcriptStatus = TranscriptStatus.DONE.name,
            keepFlag = true,
            personId = 3,
            errorText = null,
            createdAt = 1_000,
            updatedAt = 2_000
        )
        val back = entity.toDomain().toEntity()
        assertEquals(entity, back)
    }

    @Test
    fun recordingNullsAndUnknownEnumsFallBackSafely() {
        val entity = RecordingEntity(
            id = 1,
            sourceType = "SOMETHING_NEW",
            filePath = "/x.aac",
            displayName = null,
            durationMs = 0,
            sizeBytes = 0,
            sourcePackage = null,
            transcriptStatus = "NOT_A_STATUS",
            keepFlag = false,
            personId = null,
            errorText = "boom",
            createdAt = 0,
            updatedAt = 0
        )
        val domain = entity.toDomain()
        assertEquals(RecordingSourceType.IMPORTED, domain.sourceType)
        assertEquals(TranscriptStatus.PENDING, domain.transcriptStatus)
        assertNull(domain.displayName)
        assertNull(domain.sourcePackage)
        assertNull(domain.personId)
        assertEquals("boom", domain.errorText)
    }

    @Test
    fun transcriptSegmentRoundTripsAndUsesPassedRecordingId() {
        val segment = TranscriptSegment(
            id = 5,
            recordingId = 99,
            startMs = 100,
            endMs = 250,
            text = "привет",
            speakerId = 2,
            confidence = 0.75f,
            createdAt = 50
        )
        val entity = segment.toEntity(recordingId = 7)
        assertEquals(7L, entity.recordingId)
        assertEquals(segment.copy(recordingId = 7), entity.toDomain())
    }

    @Test
    fun transcriptSegmentNullOptionalsSurvive() {
        val entity = TranscriptSegmentEntity(
            id = 0,
            recordingId = 1,
            startMs = 0,
            endMs = 10,
            text = "x",
            speakerId = null,
            confidence = null,
            createdAt = 0
        )
        val domain = entity.toDomain()
        assertNull(domain.speakerId)
        assertNull(domain.confidence)
        assertEquals(entity, domain.toEntity(recordingId = 1))
    }

    @Test
    fun speakerCentroidRoundTripsThroughVectorCodec() {
        val centroid = floatArrayOf(0.1f, -0.2f, 0.3f, 0.4f)
        val entity = SpeakerEntity(
            id = 4,
            name = "Ana",
            isSelf = false,
            personId = 8,
            voiceModel = "wespeaker-v1",
            voiceDim = centroid.size,
            centroid = com.andharness.ru.domain.retrieval.VectorCodec.encode(centroid),
            sampleCount = 12,
            confidence = 0.9f,
            createdAt = 10,
            updatedAt = 20
        )
        val back = entity.toDomain().toEntity()
        assertEquals(entity.id, back.id)
        assertEquals(entity.name, back.name)
        assertEquals(entity.voiceDim, back.voiceDim)
        assertEquals(entity.sampleCount, back.sampleCount)
        assertArrayEquals(entity.centroid!!, back.centroid!!)
        assertArrayEquals(centroid, back.toDomain().centroid!!, 0.0f)
    }

    @Test
    fun speakerNullCentroidMapsToNull() {
        val entity = SpeakerEntity(
            id = 1,
            name = null,
            isSelf = true,
            personId = null,
            voiceModel = null,
            voiceDim = null,
            centroid = null,
            sampleCount = 0,
            confidence = null,
            createdAt = 0,
            updatedAt = 0
        )
        val domain = entity.toDomain()
        assertNull(domain.name)
        assertNull(domain.centroid)
        assertNull(domain.toEntity().centroid)
        assertTrue(domain.isSelf)
    }

    @Test
    fun speakerEmbeddingVectorRoundTripsThroughVectorCodec() {
        val vector = floatArrayOf(1f, 2f, 3f)
        val entity = SpeakerVoiceEmbeddingEntity(
            id = 6,
            speakerId = 4,
            recordingId = null,
            dim = vector.size,
            vector = com.andharness.ru.domain.retrieval.VectorCodec.encode(vector),
            model = "wespeaker-v1",
            quality = null,
            createdAt = 30
        )
        val domain = entity.toDomain()
        assertArrayEquals(vector, domain.vector, 0.0f)
        assertNull(domain.recordingId)
        assertNull(domain.quality)
        val back = domain.toEntity()
        assertEquals(entity.id, back.id)
        assertEquals(entity.speakerId, back.speakerId)
        assertArrayEquals(entity.vector, back.vector)
    }
}
