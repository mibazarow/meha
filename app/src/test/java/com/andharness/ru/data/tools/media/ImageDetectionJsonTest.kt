package com.andharness.ru.data.tools.media

import com.andharness.ru.data.tools.media.ImageDetectionJson.Detection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ImageDetectionJson.shape] — the sorting/capping/JSON shaping behind
 * `image_detect_objects`. ML Kit itself is device-only and is verified on-device, not here.
 */
class ImageDetectionJsonTest {

    private fun detection(label: String?, confidence: Float, left: Int = 0, top: Int = 0): Detection =
        Detection(label, confidence, left, top, width = 10, height = 20)

    private fun parse(shaped: ImageDetectionJson.Shaped): JsonArray =
        kotlinx.serialization.json.Json.parseToJsonElement(shaped.json).jsonArray

    @Test
    fun sortsByConfidenceDescending() {
        val shaped = ImageDetectionJson.shape(
            listOf(
                detection("low", 0.2f),
                detection("high", 0.95f),
                detection("mid", 0.5f),
            ),
        )
        val labels = parse(shaped).map { it.jsonObject.getValue("label").jsonPrimitive.content }
        assertEquals(listOf("high", "mid", "low"), labels)
        assertEquals(3, shaped.count)
    }

    @Test
    fun capsAtMaxResultsAndReportsTheCount() {
        val shaped = ImageDetectionJson.shape(
            listOf(
                detection("a", 0.9f),
                detection("b", 0.8f),
                detection("c", 0.7f),
            ),
            maxResults = 2,
        )
        val labels = parse(shaped).map { it.jsonObject.getValue("label").jsonPrimitive.content }
        assertEquals(listOf("a", "b"), labels)
        assertEquals(2, shaped.count)
    }

    @Test
    fun emptyResultIsAnEmptyJsonArray() {
        val shaped = ImageDetectionJson.shape(emptyList())
        assertEquals("[]", shaped.json)
        assertEquals(0, shaped.count)
    }

    @Test
    fun nonPositiveCapYieldsEmptyArray() {
        val shaped = ImageDetectionJson.shape(listOf(detection("a", 0.9f)), maxResults = -5)
        assertEquals("[]", shaped.json)
        assertEquals(0, shaped.count)
    }

    @Test
    fun blankLabelBecomesUnknown() {
        val shaped = ImageDetectionJson.shape(listOf(detection("  ", 0.5f)))
        val label = parse(shaped).first().jsonObject.getValue("label").jsonPrimitive.content
        assertEquals("unknown", label)
    }

    @Test
    fun boxAndConfidenceAreShaped() {
        val shaped = ImageDetectionJson.shape(listOf(detection("Food", 0.75f, left = 3, top = 4)))
        val item = parse(shaped).first().jsonObject
        assertEquals("Food", item.getValue("label").jsonPrimitive.content)
        assertEquals(0.75, item.getValue("confidence").jsonPrimitive.content.toDouble(), 1e-6)
        val box = item.getValue("box").jsonObject
        assertEquals(3, box.getValue("x").jsonPrimitive.content.toInt())
        assertEquals(4, box.getValue("y").jsonPrimitive.content.toInt())
        assertEquals(10, box.getValue("w").jsonPrimitive.content.toInt())
        assertEquals(20, box.getValue("h").jsonPrimitive.content.toInt())
    }

    @Test
    fun defaultCapIsTwenty() {
        val many = (1..30).map { detection("obj$it", it / 100f) }
        val shaped = ImageDetectionJson.shape(many)
        assertEquals(ImageDetectionJson.DEFAULT_MAX_RESULTS, shaped.count)
    }

    @Test
    fun everyItemHasTheExpectedShape() {
        val item = parse(ImageDetectionJson.shape(listOf(detection("Plant", 0.6f)))).first()
        assertTrue(item is JsonObject)
        assertEquals(setOf("label", "confidence", "box"), item.jsonObject.keys)
        assertTrue(item.jsonObject.getValue("box") is JsonObject)
        assertTrue(item.jsonObject.getValue("label") is JsonPrimitive)
    }
}
