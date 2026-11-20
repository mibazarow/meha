package com.andharness.ru.data.messages

import com.andharness.ru.domain.messages.StoredMessage
import com.andharness.ru.domain.retrieval.VectorCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM round-trip check of the stored-message entity ⇄ domain mapping (and the shared
 * [VectorCodec] the vector store uses). No device/Room needed: the entity is a plain data class.
 */
class StoredMessagesMappingsTest {

    @Test
    fun entityToDomainAndBackPreservesEveryField() {
        val domain = StoredMessage(
            id = 7,
            sourceType = "MESSENGER",
            packageName = "org.telegram.messenger",
            conversationKey = "tg:42",
            personId = 11,
            direction = "INCOMING",
            sender = "Алиса",
            isGroup = true,
            at = 1_700_000_000_000,
            text = "Полное тело сообщения",
            externalRef = "notif:abc",
            createdAt = 1_700_000_000_500
        )

        val roundTripped = domain.toEntity().toDomain()

        assertEquals(domain, roundTripped)
    }

    @Test
    fun nullableFieldsSurviveRoundTrip() {
        val domain = StoredMessage(
            id = 0,
            sourceType = "SMS",
            packageName = null,
            conversationKey = null,
            personId = null,
            direction = "UNKNOWN",
            sender = null,
            isGroup = false,
            at = 1,
            text = "hi",
            externalRef = null,
            createdAt = 2
        )

        assertEquals(domain, domain.toEntity().toDomain())
    }

    @Test
    fun vectorCodecRoundTripMatchesStoredEmbedding() {
        val vector = floatArrayOf(0.25f, -1f, 3.5f, 0f)
        assertArrayEquals(vector, VectorCodec.decode(VectorCodec.encode(vector)), 0.0f)
    }
}
