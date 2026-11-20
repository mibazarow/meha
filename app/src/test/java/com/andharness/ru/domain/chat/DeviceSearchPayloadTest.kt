package com.andharness.ru.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [DeviceSearchPayload]: the image/document/unknown classification of a hit and the
 * tolerant JSON round-trip that carries the cards from the `device_search` tool to the chat UI.
 */
class DeviceSearchPayloadTest {

    private fun card(
        kind: DeviceSearchCardKind = DeviceSearchCardKind.IMAGE,
        uri: String = "content://media/external/images/media/423",
        name: String = "beach.jpg",
        mime: String = "image/jpeg",
        snippet: String = ""
    ) = DeviceSearchCard(kind, uri, name, mime, snippet)

    @Test
    fun imageKindClassifiesAsImage() {
        assertEquals(DeviceSearchCardKind.IMAGE, DeviceSearchPayload.classify("IMAGE", ""))
        assertEquals(DeviceSearchCardKind.IMAGE, DeviceSearchPayload.classify("image", ""))
    }

    @Test
    fun imageMimeClassifiesAsImageEvenWithoutKind() {
        assertEquals(DeviceSearchCardKind.IMAGE, DeviceSearchPayload.classify(null, "image/png"))
        assertEquals(DeviceSearchCardKind.IMAGE, DeviceSearchPayload.classify("", "IMAGE/WEBP"))
    }

    @Test
    fun documentKindClassifiesAsDocument() {
        assertEquals(DeviceSearchCardKind.DOCUMENT, DeviceSearchPayload.classify("DOC_CHUNK", ""))
        assertEquals(DeviceSearchCardKind.DOCUMENT, DeviceSearchPayload.classify("document", ""))
    }

    @Test
    fun knownDocumentMimesClassifyAsDocument() {
        assertEquals(DeviceSearchCardKind.DOCUMENT, DeviceSearchPayload.classify(null, "application/pdf"))
        assertEquals(DeviceSearchCardKind.DOCUMENT, DeviceSearchPayload.classify("", "text/plain"))
        assertEquals(
            DeviceSearchCardKind.DOCUMENT,
            DeviceSearchPayload.classify(null, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        )
    }

    @Test
    fun unrecognisedTypeFallsBackToUnknown() {
        assertEquals(DeviceSearchCardKind.UNKNOWN, DeviceSearchPayload.classify(null, "application/octet-stream"))
        assertEquals(DeviceSearchCardKind.UNKNOWN, DeviceSearchPayload.classify("", ""))
        assertEquals(DeviceSearchCardKind.UNKNOWN, DeviceSearchPayload.classify("something", null))
    }

    @Test
    fun encodeDecodeRoundTripPreservesCards() {
        val cards = listOf(
            card(),
            card(
                kind = DeviceSearchCardKind.DOCUMENT,
                uri = "content://media/external/file/9",
                name = "lease.pdf",
                mime = "application/pdf",
                snippet = "договор аренды"
            ),
            card(kind = DeviceSearchCardKind.UNKNOWN, uri = "content://x/1", name = "mystery", mime = "application/octet-stream")
        )
        val decoded = DeviceSearchPayload.decode(DeviceSearchPayload.encode(cards))
        assertEquals(cards, decoded)
    }

    @Test
    fun decodeOfBlankOrNullIsEmpty() {
        assertTrue(DeviceSearchPayload.decode(null).isEmpty())
        assertTrue(DeviceSearchPayload.decode("").isEmpty())
        assertTrue(DeviceSearchPayload.decode("   ").isEmpty())
    }

    @Test
    fun decodeOfMalformedJsonIsEmptyAndDoesNotThrow() {
        assertTrue(DeviceSearchPayload.decode("{not json").isEmpty())
        assertTrue(DeviceSearchPayload.decode("[]").isEmpty())
    }

    @Test
    fun decodeToleratesUnknownKeysAndMissingFields() {
        val raw = """
            {"items":[{"kind":"IMAGE","uri":"content://a/1","extra":"ignored"}]}
        """.trimIndent()
        val decoded = DeviceSearchPayload.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("content://a/1", decoded[0].uri)
        assertEquals(DeviceSearchCardKind.IMAGE, decoded[0].kind)
    }

    @Test
    fun decodeDropsItemsWithoutUri() {
        val raw = """{"items":[{"kind":"IMAGE","name":"no-uri"},{"kind":"IMAGE","uri":"content://a/2"}]}"""
        val decoded = DeviceSearchPayload.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("content://a/2", decoded[0].uri)
    }
}
