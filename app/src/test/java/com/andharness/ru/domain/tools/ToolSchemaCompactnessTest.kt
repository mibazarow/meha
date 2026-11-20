package com.andharness.ru.domain.tools

import com.andharness.ru.data.agent.LlmTokenEstimator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the token cost of the tool schemas exposed on a typical turn.
 *
 * The tool-schema array dominates the fresh-chat context, and a large share used to be repeated
 * boilerplate in parameter descriptions (the shared-storage root sentence, the "идентификатор …"
 * phrasing, duplicated SSH/Git parameter texts). Two invariants keep it from creeping back:
 *
 * 1. no single parameter `description` is longer than [MAX_PARAM_DESCRIPTION_CHARS];
 * 2. the estimated serialized cost of [ToolRetriever.CORE_TOOLS] plus the next 20 catalog tools
 *    stays under [MAX_CORE_SCHEMA_TOKENS].
 *
 * The token number is produced by the same estimator the agent uses on every request
 * (`LlmTokenEstimator`), so it tracks the real per-turn payload, not a proxy.
 */
class ToolSchemaCompactnessTest {

    private val tools = DefaultTools.all()

    private fun collectParameterDescriptions(element: JsonElement, out: MutableList<String>) {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (key == "description" && value is JsonPrimitive && value.isString) {
                        out += value.content
                    } else {
                        collectParameterDescriptions(value, out)
                    }
                }
            }
            is JsonArray -> element.forEach { collectParameterDescriptions(it, out) }
            JsonNull -> Unit
            is JsonPrimitive -> Unit
        }
    }

    @Test
    fun noParameterDescriptionExceedsTheBound() {
        val descriptions = ArrayList<String>()
        for (tool in tools) {
            collectParameterDescriptions(tool.parameters, descriptions)
        }
        val worst = descriptions.maxByOrNull { it.length }
        println("TOOL_SCHEMA_METRIC max_param_description_chars=${worst?.length ?: 0}")
        assertTrue(
            "every parameter description must be ≤ $MAX_PARAM_DESCRIPTION_CHARS chars; " +
                "worst is ${worst?.length}: ${worst?.take(120)}",
            (worst?.length ?: 0) <= MAX_PARAM_DESCRIPTION_CHARS
        )
    }

    @Test
    fun corePlus20SchemaCostStaysUnderTheBudget() {
        val core = tools.filter { it.name in ToolRetriever.CORE_TOOLS }
        val extra = tools.filterNot { it.name in ToolRetriever.CORE_TOOLS }.take(20)
        val exposed = (core + extra).map { it.toLlmTool() }
        val tokens = LlmTokenEstimator.estimate(exposed)
        println(
            "TOOL_SCHEMA_METRIC core_plus20_tools=${exposed.size} " +
                "schema_tokens=$tokens threshold=$MAX_CORE_SCHEMA_TOKENS"
        )
        assertTrue(
            "CORE_TOOLS+20 serialized schema cost is $tokens tokens, over the " +
                "$MAX_CORE_SCHEMA_TOKENS budget",
            tokens <= MAX_CORE_SCHEMA_TOKENS
        )
    }

    private companion object {
        /** Longest allowed parameter `description`; enum lists are referenced, not inlined. */
        const val MAX_PARAM_DESCRIPTION_CHARS = 180

        /**
         * Estimated schema cost of the always-exposed core tools plus the BM25 addition. Raised
         * from 8_950 when the high-frequency chat management / contact-lookup / schedule-list
         * tools were restored to [ToolRetriever.CORE_TOOLS].
         */
        const val MAX_CORE_SCHEMA_TOKENS = 11_000L
    }
}
