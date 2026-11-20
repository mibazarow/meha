package com.andharness.ru.domain.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallStreamAccumulatorTest {

    private fun delta(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun ToolCallStreamAccumulator.appendAll(vararg json: String) {
        json.forEachIndexed { position, text -> append(delta(text), position, json.size) }
    }

    @Test
    fun accumulatesArgumentsAcrossChunks() {
        val acc = ToolCallStreamAccumulator()
        acc.appendAll("""{"index":0,"id":"call_1","function":{"name":"flashlight_set","arguments":"{\"sta"}}""")
        acc.appendAll("""{"index":0,"function":{"arguments":"te\":\"on\"}"}}""")

        val calls = acc.finish()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("flashlight_set", calls[0].name)
        assertEquals("""{"state":"on"}""", calls[0].arguments)
    }

    @Test
    fun recoversACallWhoseIndexIsMissing() {
        val acc = ToolCallStreamAccumulator()
        acc.appendAll("""{"id":"call_1","function":{"name":"battery_get","arguments":"{}"}}""")

        val calls = acc.finish()
        assertEquals(1, calls.size)
        assertEquals("battery_get", calls[0].name)
    }

    @Test
    fun keepsParallelCallsApartWhenEveryIndexIsMissing() {
        val acc = ToolCallStreamAccumulator()
        acc.appendAll(
            """{"id":"a","function":{"name":"wifi_get","arguments":"{}"}}""",
            """{"id":"b","function":{"name":"battery_get","arguments":"{}"}}""",
        )

        assertEquals(listOf("wifi_get", "battery_get"), acc.finish().map { it.name })
    }

    @Test
    fun buffersAFragmentWhoseIdAndNameArriveLater() {
        val acc = ToolCallStreamAccumulator()
        acc.appendAll("""{"index":0,"function":{"arguments":"{\"a\":1}"}}""")
        acc.appendAll("""{"index":0,"id":"call_9","function":{"name":"files_list"}}""")

        val calls = acc.finish()
        assertEquals(1, calls.size)
        assertEquals("call_9", calls[0].id)
        assertEquals("files_list", calls[0].name)
        assertEquals("""{"a":1}""", calls[0].arguments)
    }

    @Test
    fun distinguishesASecondCallThatReusesTheLastIndex() {
        val acc = ToolCallStreamAccumulator()
        acc.appendAll("""{"index":0,"id":"a","function":{"name":"wifi_get","arguments":"{}"}}""")
        acc.appendAll("""{"id":"b","function":{"name":"battery_get","arguments":"{}"}}""")

        assertEquals(listOf("wifi_get", "battery_get"), acc.finish().map { it.name })
    }

    @Test
    fun ordersCallsByStreamIndex() {
        val acc = ToolCallStreamAccumulator()
        acc.appendAll("""{"index":1,"id":"b","function":{"name":"second","arguments":"{}"}}""")
        acc.appendAll("""{"index":0,"id":"a","function":{"name":"first","arguments":"{}"}}""")

        assertEquals(listOf("first", "second"), acc.finish().map { it.name })
    }

    @Test
    fun givesAMissingIdAStableSyntheticOne() {
        val acc = ToolCallStreamAccumulator()
        acc.appendAll("""{"index":2,"function":{"name":"wifi_get","arguments":"{}"}}""")

        assertEquals("stream-call-2", acc.finish().single().id)
    }

    @Test
    fun dropsACallWhoseNameNeverArrived() {
        val acc = ToolCallStreamAccumulator()
        acc.appendAll("""{"index":0,"id":"call_1","function":{"arguments":"{}"}}""")

        assertTrue(acc.finish().isEmpty())
    }

    @Test
    fun finishResetsTheAccumulator() {
        val acc = ToolCallStreamAccumulator()
        acc.appendAll("""{"index":0,"id":"a","function":{"name":"wifi_get","arguments":"{}"}}""")
        acc.finish()

        assertTrue(acc.isEmpty)
        assertTrue(acc.finish().isEmpty())
    }

    @Test
    fun isEmptyReflectsWhetherAnythingArrived() {
        val acc = ToolCallStreamAccumulator()
        assertTrue(acc.isEmpty)
        acc.appendAll("""{"index":0,"function":{"arguments":"{"}}""")
        assertFalse(acc.isEmpty)
        acc.clear()
        assertTrue(acc.isEmpty)
    }
}
