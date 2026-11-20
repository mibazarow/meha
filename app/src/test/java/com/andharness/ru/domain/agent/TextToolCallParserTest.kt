package com.andharness.ru.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolCallParserTest {

    @Test
    fun parsesTheObservedTagFormWithoutParameters() {
        val calls = TextToolCallParser.parse("<tool_call> <function=device_facts_list> </tool_call>")
        assertEquals(1, calls.size)
        assertEquals("device_facts_list", calls[0].name)
        assertEquals("{}", calls[0].arguments)
    }

    @Test
    fun parsesTagFormWithParameters() {
        val calls = TextToolCallParser.parse(
            "<tool_call>\n<function=flashlight_set>\n<parameter=state>on</parameter>\n</function>\n</tool_call>"
        )
        assertEquals(1, calls.size)
        assertEquals("flashlight_set", calls[0].name)
        assertEquals("""{"state":"on"}""", calls[0].arguments)
    }

    @Test
    fun parsesSeveralBlocksInOrder() {
        val calls = TextToolCallParser.parse(
            "<tool_call><function=battery_get></function></tool_call>" +
                "<tool_call><function=wifi_get></function></tool_call>"
        )
        assertEquals(listOf("battery_get", "wifi_get"), calls.map { it.name })
        assertEquals(listOf("text-call-0", "text-call-1"), calls.map { it.id })
    }

    @Test
    fun parsesJsonForm() {
        val calls = TextToolCallParser.parse(
            """<tool_call>{"name": "files_list", "arguments": {"path": "/sdcard"}}</tool_call>"""
        )
        assertEquals(1, calls.size)
        assertEquals("files_list", calls[0].name)
        assertEquals("""{"path":"/sdcard"}""", calls[0].arguments)
    }

    @Test
    fun jsonFormWithoutArgumentsGetsAnEmptyObject() {
        val calls = TextToolCallParser.parse("""<tool_call>{"name":"device_facts_list"}</tool_call>""")
        assertEquals("{}", calls.single().arguments)
    }

    @Test
    fun aNormalAnswerProducesNoCalls() {
        assertTrue(TextToolCallParser.parse("Батарея 87%, сеть Wi-Fi.").isEmpty())
        assertTrue(TextToolCallParser.parse("").isEmpty())
        assertTrue(TextToolCallParser.parse("<tool_call>мусор без имени</tool_call>").isEmpty())
    }

    @Test
    fun removeDropsBlocksAndKeepsTheSurroundingAnswer() {
        assertEquals(
            "Проверяю.\n\nГотово.",
            TextToolCallParser.remove(
                "Проверяю.\n<tool_call><function=device_facts_list></function></tool_call>\nГотово."
            )
        )
        assertEquals("Просто ответ.", TextToolCallParser.remove("Просто ответ."))
    }

    @Test
    fun removeDropsAnUnclosedBlockWhileStreaming() {
        assertEquals("Проверяю.", TextToolCallParser.remove("Проверяю.<tool_call> <function=dev"))
        assertEquals("Проверяю.", TextToolCallParser.remove("Проверяю.<tool_ca"))
        assertEquals("Проверяю.", TextToolCallParser.remove("Проверяю.<tool_call"))
    }

    @Test
    fun removeKeepsATrailingLessThanThatIsNotAToolCallPrefix() {
        assertEquals("a < b", TextToolCallParser.remove("a < b"))
    }
}
