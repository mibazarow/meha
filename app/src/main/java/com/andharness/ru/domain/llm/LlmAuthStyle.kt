package com.andharness.ru.domain.llm

/**
 * How the API key is attached to an outgoing OpenAI-compatible request.
 *
 * Different providers expect different schemes, so a single hard-coded `Authorization: Bearer` is
 * not enough (update.md, Приоритет №1):
 * - [BEARER] — `Authorization: Bearer <key>` (OpenAI, DeepSeek, most gateways). The default.
 * - [HEADER] — the key in a caller-named header, e.g. `x-api-key: <key>` (Anthropic-style). The
 *   raw key is sent with no `Bearer ` prefix.
 * - [QUERY] — the key as a URL query parameter, e.g. `?api_key=<key>` (Google/Gemini-style).
 * - [NONE] — no auth at all; a self-hosted server (llama.cpp, Ollama, vLLM) that needs no key.
 *
 * [storageValue] is what is persisted in the encrypted preferences; [fromStorage] parses it
 * leniently and falls back to [DEFAULT] for null/unknown values.
 */
enum class LlmAuthStyle(val storageValue: String) {
    BEARER(storageValue = "BEARER"),
    HEADER(storageValue = "HEADER"),
    QUERY(storageValue = "QUERY"),
    NONE(storageValue = "NONE");

    companion object {
        val DEFAULT: LlmAuthStyle = BEARER

        /** Lenient parser for a value read from the settings store; unknown values keep the default. */
        fun fromStorage(value: String?): LlmAuthStyle {
            val raw = value?.trim().orEmpty()
            return entries.firstOrNull { it.storageValue.equals(raw, ignoreCase = true) } ?: DEFAULT
        }
    }
}
