package com.andharness.ru.data.agent

import com.andharness.ru.domain.agent.LlmContentPart
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmTool
import com.andharness.ru.domain.agent.LlmToolCall
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmTokenEstimatorTest {

    @Test
    fun emptyTextCostsNothing() {
        assertEquals(0L, LlmTokenEstimator.estimate(""))
    }

    @Test
    fun cyrillicIsMeasuredAtAboutTwoCharsPerToken() {
        val cyrillic = "привет".repeat(20) // 120 chars, ≈2 chars/token
        val latin = "x".repeat(120) // 120 chars, ≈4 chars/token

        assertEquals(60L, LlmTokenEstimator.estimate(cyrillic))
        assertEquals(30L, LlmTokenEstimator.estimate(latin))
        assertTrue(
            "Cyrillic must be estimated as more expensive than the same length of Latin",
            LlmTokenEstimator.estimate(cyrillic) > LlmTokenEstimator.estimate(latin)
        )
    }

    /**
     * CJK, emoji and other non-ASCII/non-Cyrillic text tokenises worse than Latin, so it must never
     * be measured with the Latin factor: an emoji-heavy or CJK prompt would silently overrun the
     * window. Over-counting here only trims a little early.
     */
    @Test
    fun cjkAndEmojiAreMeasuredMoreExpensivelyThanAscii() {
        val cjk = "漢字".repeat(60) // 120 chars
        val emoji = "🙂".repeat(60) // 120 chars (surrogate pairs)
        val ascii = "x".repeat(120)

        assertEquals(80L, LlmTokenEstimator.estimate(cjk))
        assertTrue(
            "CJK must cost more than the same length of ASCII",
            LlmTokenEstimator.estimate(cjk) > LlmTokenEstimator.estimate(ascii)
        )
        assertTrue(
            "emoji must cost more than the same length of ASCII",
            LlmTokenEstimator.estimate(emoji) > LlmTokenEstimator.estimate(ascii)
        )
    }

    @Test
    fun messageEstimateIncludesPerMessageOverhead() {
        val messages = listOf(LlmMessage(role = "user", content = "x".repeat(40)))

        assertEquals(14L, LlmTokenEstimator.estimate(messages))
    }

    @Test
    fun imagePartsAndToolCallArgumentsAreCounted() {
        val plain = listOf(LlmMessage(role = "user", content = "hi"))
        val withImage = listOf(
            LlmMessage(
                role = "user",
                contentParts = listOf(
                    LlmContentPart.Text("hi"),
                    LlmContentPart.ImageUrl("data:image/png;base64," + "A".repeat(400))
                )
            )
        )
        val withToolCall = listOf(
            LlmMessage(
                role = "assistant",
                toolCalls = listOf(
                    LlmToolCall(
                        id = "c1",
                        name = "files_list",
                        arguments = "{\"path\":\"/sdcard/" + "x".repeat(400) + "\"}"
                    )
                )
            )
        )

        assertTrue(
            "multimodal content parts must be counted",
            LlmTokenEstimator.estimate(withImage) > LlmTokenEstimator.estimate(plain)
        )
        assertTrue(
            "tool-call arguments must be counted",
            LlmTokenEstimator.estimate(withToolCall) > LlmTokenEstimator.estimate(plain)
        )
    }

    @Test
    fun toolSchemasAreCounted() {
        val messages = listOf(LlmMessage(role = "user", content = "hi"))
        val tools = listOf(
            LlmTool(
                name = "files_list",
                description = "d".repeat(400),
                parameters = buildJsonObject { }
            )
        )

        assertTrue(LlmTokenEstimator.estimate(tools) > 0L)
        assertTrue(
            "the tool-schema payload must count towards the request estimate",
            LlmTokenEstimator.estimate(messages, tools) > LlmTokenEstimator.estimate(messages)
        )
    }
}
