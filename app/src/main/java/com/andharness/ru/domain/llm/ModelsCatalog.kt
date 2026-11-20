package com.andharness.ru.domain.llm

/**
 * Result of discovering the models offered by an OpenAI-compatible endpoint.
 *
 * @param models            model ids in the order the server returned them.
 * @param contextById       max context length (tokens) per model id, when the server exposes it.
 * @param endpointContextFallback context reported by the server itself (llama.cpp `/props`
 *                          `default_generation_settings.n_ctx`), applied to every model that has
 *                          no per-model value.
 * @param error             honest failure message; null when the request succeeded. An empty
 *                          [models] list with a null error means "the endpoint answered but offers
 *                          no models".
 *
 * References:
 * - OpenAI `GET /models`: https://platform.openai.com/docs/api-reference/models/list
 * - llama.cpp server `/props`: https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
 */
data class ModelsCatalog(
    val models: List<String> = emptyList(),
    val contextById: Map<String, Long> = emptyMap(),
    val endpointContextFallback: Long? = null,
    val error: String? = null,
)

/** OpenAI-compatible model-list URL: `GET {baseUrl}/models`. */
fun openAiModelsUrl(endpoint: String): String =
    normalizeEndpointBase(endpoint) + "/models"

/**
 * Candidate llama.cpp `/props` URLs.
 *
 * `/props` is served at the server ROOT, not under the `/v1` prefix, so for an endpoint like
 * `http://host:8080/v1/` the root variant (`http://host:8080/props`) must be tried first. The
 * literal `{baseUrl}/props` is kept as a second candidate for servers that proxy everything.
 */
fun llamaPropsUrls(endpoint: String): List<String> {
    val base = normalizeEndpointBase(endpoint)
    val root = base.removeSuffix("/v1")
    return if (root == base) listOf("$base/props") else listOf("$root/props", "$base/props")
}
