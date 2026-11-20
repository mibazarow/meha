package com.andharness.ru.data.embedding

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pure (Android-free) construction and parsing for the EmbeddingGemma 2 HTTP API exposed by
 * `libllamaserver.so`. Kept separate from [LocalEmbeddingServer] so the URL/body/response contract is
 * unit-tested with fakes, without a device or a running server.
 *
 * Endpoint and schemas (llama.cpp `tools/server`, `handle_embeddings_impl`):
 * https://github.com/ggml-org/llama.cpp/tree/master/tools/server
 * - `POST /v1/embeddings` (OpenAI-compatible), body `{"input": ...}`;
 * - text: `input` is the string to embed;
 * - image: `input` is an object with an OpenAI content array
 *   `{"content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}]}`;
 * - response: `{"object":"list","data":[{"object":"embedding","index":0,"embedding":[ ...floats... ]}]}`.
 *
 * `java.util.Base64` is available on Android API 26+ (app minSdk 31):
 * https://developer.android.com/reference/java/util/Base64
 */
internal object EmbeddingApi {

    /** OpenAI-compatible embeddings path appended to the server root. */
    const val EMBEDDINGS_PATH = "/v1/embeddings"

    /** Media type used for the `data:` URL of image embeddings. */
    const val DEFAULT_IMAGE_MEDIA_TYPE = "image/png"

    /** `POST <root>/v1/embeddings` from the server ROOT (e.g. `http://127.0.0.1:8090`). */
    fun embeddingsUrl(rootUrl: String): String = rootUrl.trimEnd('/') + EMBEDDINGS_PATH

    /** Builds a `data:<mediaType>;base64,<payload>` URL for the raw [bytes]. */
    fun dataUrl(bytes: ByteArray, mediaType: String = DEFAULT_IMAGE_MEDIA_TYPE): String =
        "data:$mediaType;base64," + Base64.getEncoder().encodeToString(bytes)

    /** Text request body: `{"input":"<text>"}`. */
    fun textRequestJson(text: String): String = buildJsonObject {
        put("input", JsonPrimitive(text))
    }.toString()

    /** Image request body carrying a `data:` URL in an OpenAI image content array. */
    fun imageRequestJson(dataUrl: String): String = buildJsonObject {
        putJsonObject("input") {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            putJsonObject("image_url") { put("url", JsonPrimitive(dataUrl)) }
                        }
                    )
                }
            )
        }
    }.toString()

    /**
     * Reads `data[0].embedding` from an OpenAI-compatible embeddings response. Returns `null` when
     * the body is blank, malformed, has no `data`/`embedding`, is empty, or contains a non-numeric
     * element (a partially-parsed vector is never returned).
     */
    fun parseFirstEmbedding(responseBody: String?): FloatArray? {
        if (responseBody.isNullOrBlank()) return null
        val root = runCatching { Json.parseToJsonElement(responseBody) }.getOrNull() as? JsonObject
            ?: return null
        val data = root["data"] as? JsonArray ?: return null
        val first = data.firstOrNull() as? JsonObject ?: return null
        val embedding = first["embedding"] as? JsonArray ?: return null
        if (embedding.isEmpty()) return null
        val values = embedding.map { (it as? JsonPrimitive)?.floatOrNull }
        if (values.any { it == null }) return null
        return FloatArray(values.size) { values[it]!! }
    }
}
