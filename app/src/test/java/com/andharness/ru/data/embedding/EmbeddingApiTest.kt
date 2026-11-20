package com.andharness.ru.data.embedding

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the EmbeddingGemma 2 HTTP contract: the URL, the text/image request bodies and the
 * `data[0].embedding` response parser. No device, server or Android involved.
 */
class EmbeddingApiTest {

    // --- URL --------------------------------------------------------------------------------

    @Test
    fun embeddingsUrlAppendsV1PathToRoot() {
        assertEquals(
            "http://127.0.0.1:8090/v1/embeddings",
            EmbeddingApi.embeddingsUrl("http://127.0.0.1:8090"),
        )
    }

    @Test
    fun embeddingsUrlToleratesTrailingSlash() {
        assertEquals(
            "http://127.0.0.1:8090/v1/embeddings",
            EmbeddingApi.embeddingsUrl("http://127.0.0.1:8090/"),
        )
    }

    // --- data URL ---------------------------------------------------------------------------

    @Test
    fun dataUrlEncodesBytesAsBase64Png() {
        // 0x01 0x02 0x03 -> "AQID".
        assertEquals(
            "data:image/png;base64,AQID",
            EmbeddingApi.dataUrl(byteArrayOf(1, 2, 3)),
        )
    }

    // --- text request -----------------------------------------------------------------------

    @Test
    fun textRequestCarriesInputString() {
        val obj = Json.parseToJsonElement(EmbeddingApi.textRequestJson("hello world")).jsonObject
        assertEquals("hello world", obj["input"]!!.jsonPrimitive.content)
    }

    @Test
    fun textRequestEscapesSpecialCharacters() {
        val text = "line1\nline2 \"quoted\" \\ and ünïcode"
        val obj = Json.parseToJsonElement(EmbeddingApi.textRequestJson(text)).jsonObject
        assertEquals(text, obj["input"]!!.jsonPrimitive.content)
    }

    // --- image request ----------------------------------------------------------------------

    @Test
    fun imageRequestMatchesOpenAiContentArrayShape() {
        val dataUrl = "data:image/png;base64,AQID"
        val input = Json.parseToJsonElement(EmbeddingApi.imageRequestJson(dataUrl))
            .jsonObject["input"]!!.jsonObject
        val content = input["content"]!!.jsonArray
        assertEquals(1, content.size)
        val part = content[0].jsonObject
        assertEquals("image_url", part["type"]!!.jsonPrimitive.content)
        assertEquals(dataUrl, part["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    // --- response parsing -------------------------------------------------------------------

    @Test
    fun parsesFirstEmbeddingFromOaiResponse() {
        val body = """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}]}"""
        val vec = EmbeddingApi.parseFirstEmbedding(body)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), vec!!, 0.0f)
    }

    @Test
    fun parsesAllSevenHundredSixtyEightDimensions() {
        val values = (0 until 768).joinToString(",") { "0.5" }
        val body = """{"data":[{"embedding":[$values]}]}"""
        val vec = EmbeddingApi.parseFirstEmbedding(body)
        assertEquals(768, vec!!.size)
        assertTrue(vec.all { it == 0.5f })
    }

    @Test
    fun takesTheFirstEntryWhenSeveralAreReturned() {
        val body = """{"data":[{"embedding":[1.0]},{"embedding":[2.0]}]}"""
        assertArrayEquals(floatArrayOf(1.0f), EmbeddingApi.parseFirstEmbedding(body)!!, 0.0f)
    }

    @Test
    fun returnsNullOnBlankBody() {
        assertNull(EmbeddingApi.parseFirstEmbedding(null))
        assertNull(EmbeddingApi.parseFirstEmbedding(""))
        assertNull(EmbeddingApi.parseFirstEmbedding("   "))
    }

    @Test
    fun returnsNullOnMalformedJson() {
        assertNull(EmbeddingApi.parseFirstEmbedding("{"))
        assertNull(EmbeddingApi.parseFirstEmbedding("not json"))
    }

    @Test
    fun returnsNullWhenDataIsMissing() {
        assertNull(EmbeddingApi.parseFirstEmbedding("""{"object":"list"}"""))
    }

    @Test
    fun returnsNullWhenDataIsEmpty() {
        assertNull(EmbeddingApi.parseFirstEmbedding("""{"data":[]}"""))
    }

    @Test
    fun returnsNullWhenEmbeddingIsMissing() {
        assertNull(EmbeddingApi.parseFirstEmbedding("""{"data":[{"index":0}]}"""))
    }

    @Test
    fun returnsNullWhenEmbeddingIsEmpty() {
        assertNull(EmbeddingApi.parseFirstEmbedding("""{"data":[{"embedding":[]}]}"""))
    }

    @Test
    fun returnsNullWhenAnyElementIsNotNumeric() {
        assertNull(EmbeddingApi.parseFirstEmbedding("""{"data":[{"embedding":[0.1,"x"]}]}"""))
    }

    @Test
    fun returnsNullWhenDataEntryIsNotAnObject() {
        assertNull(EmbeddingApi.parseFirstEmbedding("""{"data":[42]}"""))
    }

    @Test
    fun returnsNullOnErrorResponse() {
        assertNull(
            EmbeddingApi.parseFirstEmbedding(
                """{"error":{"message":"This server does not support embeddings"}}"""
            )
        )
    }
}
