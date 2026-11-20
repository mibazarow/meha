package com.andharness.ru.data.agent

import com.andharness.ru.domain.agent.LlmContentPart
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmTool

/**
 * Dependency-free token estimator for an OpenAI-compatible chat-completions request.
 *
 * Three reasons this is not a naive `length / 4`:
 * 1. BPE vocabularies tokenise Cyrillic far worse than Latin (≈2 chars/token vs ≈4), so a single
 *    Latin factor systematically under-counts Russian prompts. The two scripts are measured apart.
 * 2. Everything outside ASCII and Cyrillic — CJK, emoji, combining marks — tokenises worse still
 *    (often one token per character or per glyph), so it gets its own, deliberately pessimistic
 *    bucket. Over-counting only trims a little early; under-counting overruns the real window.
 * 3. The request payload is not just message text: multimodal `contentParts`, assistant tool-call
 *    names/arguments and the whole tool-schema array all travel on the wire and must count, or the
 *    "context budget" check sees only a fraction of the real request.
 */
internal object LlmTokenEstimator {
    private const val CHARS_PER_TOKEN_LATIN = 4.0
    private const val CHARS_PER_TOKEN_CYRILLIC = 2.0

    /** CJK, emoji and other non-ASCII/non-Cyrillic text: counted pessimistically on purpose. */
    private const val CHARS_PER_TOKEN_OTHER = 1.5

    /** Per-message framing overhead (role, separators, JSON keys) in tokens. */
    private const val MESSAGE_OVERHEAD_TOKENS = 4L

    /** Per-tool framing overhead (type/function wrapper and JSON keys) in tokens. */
    private const val TOOL_OVERHEAD_TOKENS = 8L

    /**
     * Estimates the tokens of a bare string, measuring ASCII, Cyrillic and everything else (CJK,
     * emoji, diacritics) as three separate buckets.
     */
    fun estimate(text: String): Long {
        if (text.isEmpty()) return 0L
        var cyrillic = 0
        var other = 0
        for (ch in text) {
            when {
                isCyrillic(ch) -> cyrillic++
                ch.code < ASCII_LIMIT -> Unit
                else -> other++
            }
        }
        val ascii = text.length - cyrillic - other
        return (ascii / CHARS_PER_TOKEN_LATIN +
            cyrillic / CHARS_PER_TOKEN_CYRILLIC +
            other / CHARS_PER_TOKEN_OTHER).toLong()
    }

    /**
     * Estimates the tokens of the full request: every message (text, multimodal parts, tool-call
     * name + arguments) plus every tool schema that will be serialised alongside them.
     */
    fun estimate(messages: List<LlmMessage>, tools: List<LlmTool> = emptyList()): Long {
        var total = 0L
        for (message in messages) {
            total += MESSAGE_OVERHEAD_TOKENS
            message.content?.let { total += estimate(it) }
            message.contentParts?.forEach { part ->
                total += when (part) {
                    is LlmContentPart.Text -> estimate(part.text)
                    is LlmContentPart.ImageUrl -> estimate(part.dataUri)
                }
            }
            message.toolCalls?.forEach { call ->
                total += estimate(call.name) + estimate(call.arguments)
            }
        }
        total += estimate(tools)
        return total
    }

    /** Estimates only the tool-schema payload, so callers can cache it across loop iterations. */
    fun estimate(tools: List<LlmTool>): Long {
        var total = 0L
        for (tool in tools) {
            total += TOOL_OVERHEAD_TOKENS
            total += estimate(tool.name) + estimate(tool.description) + estimate(tool.parameters.toString())
        }
        return total
    }

    private fun isCyrillic(ch: Char): Boolean = ch in '\u0400'..'\u04FF'

    /** End of printable ASCII; anything above it is measured in the pessimistic bucket. */
    private const val ASCII_LIMIT = 0x80
}
