package com.andharness.ru.domain.llm

/**
 * Canonical endpoint base: surrounding whitespace is trimmed and a single trailing slash removed, so
 * a caller can append an exact path suffix (`/models`, `/chat/completions`, …) without ever doubling
 * the separator. One shared helper so the catalog, the chat client and the vision probe cannot drift.
 *
 * Pure Kotlin — unit-testable on the JVM.
 * References:
 * - `String.trim()` — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/trim.html
 * - `String.removeSuffix()` — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/remove-suffix.html
 */
fun normalizeEndpointBase(url: String): String = url.trim().removeSuffix("/")

/**
 * OpenAI-compatible chat-completions URL for [endpoint].
 *
 * A single helper keeps the chat client and the vision probe from drifting apart: both must trim a
 * trailing slash so the shared suffix is appended exactly once.
 * Reference: https://platform.openai.com/docs/api-reference/chat/create
 */
fun openAiChatCompletionsUrl(endpoint: String): String =
    normalizeEndpointBase(endpoint) + "/chat/completions"
