package com.andharness.ru.data.vision

import com.andharness.ru.domain.llm.openAiChatCompletionsUrl
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pure, JVM-testable policy behind the Vision detection (update.md §3.2). No Android APIs here:
 * allowlist matching, the cache key, cache freshness, the allowlist-vs-cache-vs-probe decision and
 * the probe-response classification can all be unit-tested without a device.
 *
 * References:
 * - OpenAI vision (image_url content part): https://platform.openai.com/docs/guides/vision
 * - Anthropic vision: https://docs.anthropic.com/en/docs/build-with-claude/vision
 */
object VisionPolicy {

    /**
     * Known vision-capable model families, matched by id. Kept lowercase; matching is
     * case-insensitive and boundary-aware (see [allowlistHit]) to avoid matching inside an
     * unrelated id that merely contains the digits.
     */
    val ALLOWLIST: List<String> = listOf(
        "gpt-4o", "gpt-4.1", "gpt-5",
        "o3", "o4",
        "claude-3", "claude-4", "claude-sonnet", "claude-opus", "claude-haiku",
        "gemini",
        "qwen-vl", "qwen2-vl", "qwen2.5-vl",
        "llava", "moondream", "pixtral", "internvl", "minicpm-v",
    )

    /**
     * How long a cached probe result is trusted. The cache is keyed by model+endpoint, so a model
     * or endpoint change already forces a re-probe (§3.2); this TTL is only a safety net so a very
     * old verdict is refreshed eventually. `vision_check` always re-probes immediately.
     */
    const val CACHE_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * Returns the allowlist pattern that [model] matches, or null.
     *
     * Matching is a case-insensitive substring test. The very short entries (`o3`, `o4`) are
     * additionally required to sit on a *token boundary* (start/end of the id or a
     * non-alphanumeric character), so they do not match digits inside an unrelated id — while
     * longer family names still match their versioned forms (`moondream2`, `llava-1.5-7b`).
     */
    fun allowlistHit(model: String): String? {
        val id = model.trim().lowercase()
        if (id.isEmpty()) return null
        return ALLOWLIST.firstOrNull { pattern ->
            if (pattern.length <= SHORT_PATTERN_LENGTH) {
                containsOnTokenBoundary(id, pattern)
            } else {
                id.contains(pattern)
            }
        }
    }

    /** Patterns at or below this length must match on a token boundary. */
    private const val SHORT_PATTERN_LENGTH = 3

    private fun containsOnTokenBoundary(id: String, pattern: String): Boolean {
        var from = 0
        while (true) {
            val index = id.indexOf(pattern, from)
            if (index < 0) return false
            val beforeOk = index == 0 || !id[index - 1].isLetterOrDigit()
            val afterIndex = index + pattern.length
            val afterOk = afterIndex >= id.length || !id[afterIndex].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = index + 1
        }
    }

    /**
     * Stable cache identity for a (model, endpoint) pair. Case and a trailing slash are ignored so
     * `GPT-4o @ https://api.example.com/v1/` and `gpt-4o @ https://api.example.com/v1` collapse to
     * the same entry.
     */
    fun cacheKey(model: String, endpoint: String): String =
        model.trim().lowercase() + "|" + endpoint.trim().lowercase().removeSuffix("/")

    /** True when [checkedAt] is a real timestamp within [ttlMs] of [now]. */
    fun isFresh(checkedAt: Long, now: Long, ttlMs: Long = CACHE_TTL_MS): Boolean =
        checkedAt > 0 && now >= checkedAt && (now - checkedAt) < ttlMs

    /**
     * Decides, without any network access, whether the allowlist answers, a fresh cache answers, or
     * a probe is required. Allowlist wins over cache (a known model needs no probing); a stale or
     * missing cache entry on a non-allowlisted model always falls through to [VisionDecision.Probe].
     */
    fun decide(model: String, cachedSupported: Boolean?, cacheFresh: Boolean): VisionDecision {
        allowlistHit(model)?.let { return VisionDecision.Allowlisted(it) }
        if (cachedSupported != null && cacheFresh) return VisionDecision.Cached(cachedSupported)
        return VisionDecision.Probe
    }

    /** OpenAI-compatible chat-completions URL for [endpoint] (shared with the chat client). */
    fun probeUrl(endpoint: String): String = openAiChatCompletionsUrl(endpoint)

    /** Minimal non-streaming chat request carrying a 1×1 PNG as an inline `image_url` part. */
    fun probeRequestBody(model: String, pngBase64: String): String = buildJsonObject {
        if (model.isNotBlank()) put("model", JsonPrimitive(model))
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive("ping"))
                    })
                    add(buildJsonObject {
                        put("type", JsonPrimitive("image_url"))
                        putJsonObject("image_url") {
                            put("url", JsonPrimitive("data:image/png;base64,$pngBase64"))
                        }
                    })
                })
            })
        })
        put("max_tokens", JsonPrimitive(1))
        put("stream", JsonPrimitive(false))
    }.toString()

    /**
     * Translates the probe HTTP response into a verdict.
     *
     * - 2xx → [VisionSupport.SUPPORTED] (the provider accepted the image part).
     * - A 4xx error whose body mentions images/vision/modality → [VisionSupport.UNSUPPORTED].
     * - Anything else (5xx, auth/not-found errors, gateway errors, a body we cannot interpret) →
     *   [VisionSupport.UNKNOWN]: we never claim support, and we never claim refusal either.
     */
    fun classifyProbeResponse(statusCode: Int, body: String): VisionSupport = when {
        statusCode in 200..299 -> VisionSupport.SUPPORTED
        statusCode in 400..499 && mentionsVision(body) -> VisionSupport.UNSUPPORTED
        else -> VisionSupport.UNKNOWN
    }

    private val VISION_KEYWORDS = listOf("image", "vision", "multimodal", "modality")

    private fun mentionsVision(body: String): Boolean {
        val text = body.lowercase()
        return VISION_KEYWORDS.any { text.contains(it) }
    }

    /** Human-readable, honest status text for the `vision_status` tool. */
    fun describe(state: VisionState): String {
        val support = when (state.support) {
            VisionSupport.SUPPORTED -> "поддерживается"
            VisionSupport.UNSUPPORTED -> "не поддерживается"
            VisionSupport.UNKNOWN -> "неизвестно"
        }
        val source = when (state.source) {
            VisionSource.ALLOWLIST -> "allowlist"
            VisionSource.CACHE -> "кэш"
            VisionSource.PROBE -> "проба"
            VisionSource.UNKNOWN -> "нет данных"
        }
        return buildString {
            appendLine("Модель: ${state.model.ifBlank { "—" }}")
            appendLine("Эндпоинт: ${state.endpoint.ifBlank { "—" }}")
            appendLine("Vision: $support")
            appendLine("Источник: $source")
            append("Детали: ${state.detail}")
        }
    }
}
