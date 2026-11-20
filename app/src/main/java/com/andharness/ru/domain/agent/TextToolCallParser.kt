package com.andharness.ru.domain.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Recovers tool calls that a model emitted as TEXT markup instead of a native `tool_calls` delta.
 *
 * Some OpenAI-compatible providers/models ignore the request's `tools` array and answer with the
 * call written out literally, e.g.
 *
 * ```
 * <tool_call> <function=device_facts_list> </tool_call>
 * <tool_call><function=flashlight_set><parameter=state>on</parameter></function></tool_call>
 * <tool_call>{"name": "device_facts_list", "arguments": {}}</tool_call>
 * ```
 *
 * Without this the markup is stored and shown in the chat while the intended action never runs.
 * [parse] turns such blocks into real [LlmToolCall]s so the agent executes them; [remove] strips the
 * markup from the visible answer. Both are pure and JVM-testable.
 *
 * Honest limit: only the `<tool_call>…</tool_call>` wrapper is recognised. A model that invents some
 * other envelope still leaks its text — no context-free filter can guess an unknown syntax.
 */
object TextToolCallParser {

    private val BLOCK = Regex("""<\s*tool_call\s*>([\s\S]*?)<\s*/\s*tool_call\s*>""", RegexOption.IGNORE_CASE)

    /** `<function=name>…` with optional `<parameter=key>value</parameter>` children. */
    private val FUNCTION_TAG = Regex(
        """<\s*function\s*=\s*([A-Za-z0-9_.\-]+)\s*>([\s\S]*?)(?:<\s*/\s*function\s*>|$)""",
        RegexOption.IGNORE_CASE
    )

    private val PARAMETER_TAG = Regex(
        """<\s*parameter\s*=\s*([A-Za-z0-9_.\-]+)\s*>([\s\S]*?)<\s*/\s*parameter\s*>""",
        RegexOption.IGNORE_CASE
    )

    /** An open block whose closing tag has not arrived yet (live streaming). */
    private val UNCLOSED_BLOCK = Regex("""<\s*tool_call\s*>[\s\S]*$""", RegexOption.IGNORE_CASE)

    /** A trailing fragment of the opening tag, e.g. `<tool_ca`, so it never flashes while streaming. */
    private val PARTIAL_OPEN_TAG = Regex("""<\s*t(?:o(?:o(?:l(?:_(?:c(?:a(?:l(?:l)?)?)?)?)?)?)?)?$""", RegexOption.IGNORE_CASE)

    /**
     * Every complete tool call written as markup in [text], in order. Empty when there is none, so a
     * normal answer is never affected.
     */
    fun parse(text: String): List<LlmToolCall> {
        if (text.isEmpty()) return emptyList()
        val calls = mutableListOf<LlmToolCall>()
        for (block in BLOCK.findAll(text)) {
            calls += parseBlock(block.groupValues[1], calls.size)
        }
        return calls
    }

    /** [text] with every tool-call block removed; the surrounding answer is left intact. */
    fun remove(text: String): String {
        if (text.isEmpty()) return text
        var out = BLOCK.replace(text, "")
        out = UNCLOSED_BLOCK.replace(out, "")
        out = PARTIAL_OPEN_TAG.replace(out, "")
        return out
    }

    private fun parseBlock(inner: String, offset: Int): List<LlmToolCall> {
        val tagged = FUNCTION_TAG.findAll(inner).toList()
        if (tagged.isNotEmpty()) {
            return tagged.mapIndexed { index, match ->
                LlmToolCall(
                    id = "text-call-${offset + index}",
                    name = match.groupValues[1],
                    arguments = parametersJson(match.groupValues[2]),
                )
            }
        }
        val json = jsonCall(inner) ?: return emptyList()
        return listOf(json.copy(id = "text-call-$offset"))
    }

    private fun parametersJson(body: String): String {
        val parameters = PARAMETER_TAG.findAll(body).toList()
        if (parameters.isEmpty()) return "{}"
        return parameters.joinToString(prefix = "{", postfix = "}") { match ->
            "${JsonPrimitive(match.groupValues[1])}:${JsonPrimitive(match.groupValues[2].trim())}"
        }
    }

    /** The Qwen/Hermes JSON form: `{"name": "...", "arguments": {…}}` inside the wrapper. */
    private fun jsonCall(inner: String): LlmToolCall? {
        val start = inner.indexOf('{')
        val end = inner.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching {
            Json.parseToJsonElement(inner.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val arguments = when (val raw = obj["arguments"]) {
            null -> "{}"
            is JsonObject -> raw.toString()
            is JsonPrimitive -> raw.content.takeIf { it.isNotBlank() } ?: "{}"
            else -> raw.toString()
        }
        return LlmToolCall(id = "", name = name, arguments = arguments)
    }
}
