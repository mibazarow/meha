package com.andharness.ru.data.llm

import com.andharness.ru.domain.serialization.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure parsers for OpenAI-compatible model discovery. Kept free of Android/OkHttp so they are
 * directly unit-testable on the JVM.
 *
 * Tolerated `/models` shapes:
 * - standard `{"object":"list","data":[{"id":"..."}]}`
 * - `{"models":[{"name"|"model"|"id":"..."}]}` (Ollama-native / llama.cpp)
 * - a plain top-level array `[{"id":"..."}]`
 * - a single object carrying an id
 *
 * Per-model context is read from the first present of `context_length`, `max_model_len`,
 * `context_window`, `max_context_length`, `n_ctx`, `limit.context` (OpenAI-compatible gateways
 * such as neuraldeep), `meta.n_ctx`, `meta.n_ctx_train`, `top_provider.context_length`,
 * `details.context_length`.
 *
 * References:
 * - OpenAI list models: https://platform.openai.com/docs/api-reference/models/list
 * - OpenRouter model objects (`top_provider.context_length`): https://openrouter.ai/docs/api-reference/list-available-models
 * - llama.cpp server `/props`: https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
 */
object ModelsCatalogParser {

    private val json = AppJson.LenientIsLenient

    private val ID_KEYS = listOf("id", "model", "name")
    private val CONTEXT_KEYS = listOf(
        "context_length", "max_model_len", "context_window", "max_context_length", "n_ctx",
    )

    fun parseModels(body: String): ParsedModels {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return ParsedModels(malformed = true)
        if (root !is JsonObject && root !is JsonArray) return ParsedModels(malformed = true)

        val entries: List<JsonObject> = when (root) {
            is JsonArray -> root.filterIsInstance<JsonObject>()
            is JsonObject -> {
                when (val data = root["data"] ?: root["models"]) {
                    is JsonArray -> data.filterIsInstance<JsonObject>()
                    // A single bare model object (id at the top level).
                    else -> if (ID_KEYS.any { root[it] is JsonPrimitive }) listOf(root) else emptyList()
                }
            }
            else -> emptyList()
        }

        val ids = LinkedHashSet<String>()
        val contextById = LinkedHashMap<String, Long>()
        for (entry in entries) {
            val id = ID_KEYS.firstNotNullOfOrNull { key ->
                (entry[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            } ?: continue
            ids += id
            extractContext(entry)?.let { contextById[id] = it }
        }
        return ParsedModels(models = ids.toList(), contextById = contextById)
    }

    /**
     * Context length from a llama.cpp `/props` payload:
     * `default_generation_settings.n_ctx`, then top-level `n_ctx`, then `n_ctx_train`.
     * Returns null when absent or malformed.
     */
    fun parseProps(body: String): Long? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        val gen = root["default_generation_settings"] as? JsonObject
        return longOf(gen?.get("n_ctx"))
            ?: longOf(root["n_ctx"])
            ?: longOf(gen?.get("n_ctx_train"))
            ?: longOf(root["n_ctx_train"])
    }

    private fun extractContext(entry: JsonObject): Long? {
        CONTEXT_KEYS.forEach { key -> longOf(entry[key])?.let { return it } }
        // OpenAI-compatible gateways (e.g. neuraldeep) nest the limit under `limit`:
        // `{"limit":{"context":1048576,"output":384000}}`.
        (entry["limit"] as? JsonObject)?.let { limit ->
            longOf(limit["context"])?.let { return it }
        }
        (entry["meta"] as? JsonObject)?.let { meta ->
            longOf(meta["n_ctx"])?.let { return it }
            longOf(meta["n_ctx_train"])?.let { return it }
        }
        (entry["top_provider"] as? JsonObject)?.let { provider ->
            longOf(provider["context_length"])?.let { return it }
        }
        (entry["details"] as? JsonObject)?.let { details ->
            longOf(details["context_length"])?.let { return it }
        }
        return null
    }

    private fun longOf(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        // JsonPrimitive.content is the literal text for numbers and the raw value for strings, so
        // this accepts both `128000` and `"128000"` without depending on serialization extensions.
        val value = primitive.content.trim().toLongOrNull() ?: return null
        return value.takeIf { it > 0 }
    }
}
